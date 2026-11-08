<?php

namespace App\Http\Controllers;

use App\Models\{Trip, TripPost, TripPostLike};
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
        $posts = $trip->posts()->with(['photos', 'member.user:id,name,avatar_path'])->withCount('likes')
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
        $post->load(['photos', 'member.user:id,name,avatar_path'])->loadCount('likes');
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
            'liked' => (bool) $post->liked,
            'can_delete' => $this->canDelete($post, $trip, $memberId, $request),
        ];
    }
}
