<?php

namespace App\Http\Controllers;

use App\Models\{Trip, TripPost, TripPostComment, TripPostLike};
use App\Support\PublicImages;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\{DB, Gate, Storage};

/** The trip's photo feed. Every active member can post, like, and delete their own posts; the organizer can remove any post. */
class TripPostController extends Controller
{
    private const PAGE = 15;

    public function index(Request $request, Trip $trip)
    {
        Gate::authorize('view', $trip);
        $memberId = $this->memberId($request, $trip);
        $posts = $trip->posts()->with(['photos', 'member.user:id,name,avatar_path'])->withCount(['likes', 'comments'])
            ->withExists(['likes as liked' => fn ($q) => $q->where('trip_member_id', $memberId)])
            ->when($request->integer('before'), fn ($q, $before) => $q->where('id', '<', $before))
            ->orderByDesc('id')->limit(self::PAGE + 1)->get();
        $more = $posts->count() > self::PAGE;
        $posts = $posts->take(self::PAGE);
        return response()->json([
            'data' => $posts->map(fn ($post) => $this->present($post, $trip, $memberId, $request))->values(),
            'next_before' => $more ? $posts->last()->id : null,
        ]);
    }

    public function store(Request $request, Trip $trip)
    {
        Gate::authorize('view', $trip);
        $request->validate([
            'caption' => 'nullable|string|max:2000',
            'photos' => 'required|array|min:1|max:10',
            'photos.*' => 'required|image|mimes:jpg,jpeg,png,webp|max:10240',
        ], ['photos.required' => 'Add at least one photo.', 'photos.max' => 'Post up to 10 photos at a time.']);
        $memberId = $this->memberId($request, $trip);
        $post = DB::transaction(function () use ($request, $trip, $memberId) {
            $post = TripPost::create(['trip_id' => $trip->id, 'trip_member_id' => $memberId, 'caption' => trim((string) $request->input('caption')) ?: null]);
            foreach (array_values($request->file('photos')) as $position => $file) {
                [$width, $height] = @getimagesize($file->getRealPath()) ?: [null, null];
                $post->photos()->create(['path' => PublicImages::store($file, 'trip-photos'), 'width' => $width, 'height' => $height, 'position' => $position]);
            }
            return $post;
        });
        $post->load(['photos', 'member.user:id,name,avatar_path'])->loadCount(['likes', 'comments']);
        $post->liked = false;
        return response()->json(['data' => $this->present($post, $trip, $memberId, $request)], 201);
    }

    public function destroy(Request $request, Trip $trip, TripPost $post)
    {
        abort_unless($post->trip_id === $trip->id, 404);
        Gate::authorize('view', $trip);
        abort_unless($this->canDelete($post, $trip, $this->memberId($request, $trip), $request), 403, 'Only the person who posted this or the organizer can delete it.');
        $paths = $post->photos()->pluck('path')->all();
        DB::transaction(function () use ($post) {
            $post->likes()->delete();
            $post->comments()->delete();
            $post->photos()->delete();
            $post->delete();
        });
        Storage::disk('local')->delete($paths);
        return response()->noContent();
    }

    /** Toggles the viewer's heart on a post. */
    public function like(Request $request, Trip $trip, TripPost $post)
    {
        abort_unless($post->trip_id === $trip->id, 404);
        Gate::authorize('view', $trip);
        $memberId = $this->memberId($request, $trip);
        $existing = TripPostLike::where('trip_post_id', $post->id)->where('trip_member_id', $memberId)->first();
        $existing ? $existing->delete() : TripPostLike::firstOrCreate(['trip_post_id' => $post->id, 'trip_member_id' => $memberId]);
        return response()->json(['data' => ['liked' => ! $existing, 'likes_count' => $post->likes()->count()]]);
    }

    /** A post's comments, oldest first. */
    public function comments(Request $request, Trip $trip, TripPost $post)
    {
        abort_unless($post->trip_id === $trip->id, 404);
        Gate::authorize('view', $trip);
        $memberId = $this->memberId($request, $trip);
        $comments = $post->comments()->with('member.user:id,name,avatar_path')->orderBy('id')->limit(200)->get();
        return response()->json(['data' => $comments->map(fn ($c) => $this->presentComment($c, $post, $trip, $memberId, $request))->values()]);
    }

    public function comment(Request $request, Trip $trip, TripPost $post)
    {
        abort_unless($post->trip_id === $trip->id, 404);
        Gate::authorize('view', $trip);
        $data = $request->validate(['body' => 'required|string|max:1000'], ['body.required' => 'Write a comment first.']);
        $body = trim($data['body']);
        abort_if($body === '', 422, 'Write a comment first.');
        $memberId = $this->memberId($request, $trip);
        abort_unless($memberId, 403);
        $comment = $post->comments()->create(['trip_member_id' => $memberId, 'body' => $body])->load('member.user:id,name,avatar_path');
        return response()->json(['data' => $this->presentComment($comment, $post, $trip, $memberId, $request), 'comments_count' => $post->comments()->count()], 201);
    }

    /** The comment's author, the post's author or the organizer can remove a comment. */
    public function destroyComment(Request $request, Trip $trip, TripPost $post, TripPostComment $comment)
    {
        abort_unless($post->trip_id === $trip->id && $comment->trip_post_id === $post->id, 404);
        Gate::authorize('view', $trip);
        abort_unless($this->canDeleteComment($comment, $post, $trip, $this->memberId($request, $trip), $request), 403, 'Only the person who wrote this, the post\'s author or the organizer can remove it.');
        $comment->delete();
        return response()->json(['comments_count' => $post->comments()->count()]);
    }

    private function canDeleteComment(TripPostComment $comment, TripPost $post, Trip $trip, ?int $memberId, Request $request): bool
    {
        return $comment->trip_member_id === $memberId || $post->trip_member_id === $memberId || $trip->organizer_user_id === $request->user()->id;
    }

    private function presentComment(TripPostComment $comment, TripPost $post, Trip $trip, ?int $memberId, Request $request): array
    {
        return [
            'id' => $comment->id, 'body' => $comment->body, 'created_at' => $comment->created_at?->toIso8601String(),
            'author' => ['member_id' => $comment->trip_member_id, 'name' => $comment->member?->user?->name ?? 'Traveler', 'avatar_url' => $comment->member?->user?->avatar_url],
            'can_delete' => $this->canDeleteComment($comment, $post, $trip, $memberId, $request),
        ];
    }

    public function photo(string $file)
    {
        return PublicImages::serve('trip-photos', $file);
    }

    private function memberId(Request $request, Trip $trip): ?int
    {
        return $trip->members()->where('user_id', $request->user()->id)->value('id');
    }

    private function canDelete(TripPost $post, Trip $trip, ?int $memberId, Request $request): bool
    {
        return $post->trip_member_id === $memberId || $trip->organizer_user_id === $request->user()->id;
    }

    private function present(TripPost $post, Trip $trip, ?int $memberId, Request $request): array
    {
        return [
            'id' => $post->id,
            'caption' => $post->caption,
            'created_at' => $post->created_at?->toIso8601String(),
            'author' => ['member_id' => $post->trip_member_id, 'name' => $post->member?->user?->name ?? 'Traveler', 'avatar_url' => $post->member?->user?->avatar_url],
            'photos' => $post->photos->map(fn ($photo) => ['url' => PublicImages::url($photo->path), 'width' => $photo->width, 'height' => $photo->height])->values(),
            'likes_count' => (int) $post->likes_count,
            'comments_count' => (int) ($post->comments_count ?? 0),
            'liked' => (bool) $post->liked,
            'can_delete' => $this->canDelete($post, $trip, $memberId, $request),
        ];
    }
}
