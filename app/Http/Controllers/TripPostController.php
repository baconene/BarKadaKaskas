<?php

namespace App\Http\Controllers;

use App\Models\{Trip, TripPost, TripPostComment, TripPostLike};
use App\Support\{PublicImages, PublicVideos};
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
            'photos' => 'required_without:videos|array|max:10',
            'photos.*' => 'required|image|mimes:jpg,jpeg,png,webp|max:10240',
            // Videos (up to 50 MB each), each with a still image taken from it by the app and its length.
            'videos' => 'nullable|array|max:4',
            'videos.*' => 'required|file|mimetypes:video/mp4,video/quicktime,video/webm,video/3gpp|max:51200',
            'video_posters' => 'required_with:videos|array',
            'video_posters.*' => 'required|image|mimes:jpg,jpeg,png,webp|max:10240',
            'video_durations' => 'nullable|array', 'video_durations.*' => 'nullable|integer|min:0|max:3600000',
            // Where the phone was when posting, and per photo what its own GPS data said (read before resizing).
            'latitude' => 'nullable|numeric|between:-90,90|required_with:longitude', 'longitude' => 'nullable|numeric|between:-180,180|required_with:latitude',
            'photo_locations' => 'nullable|array|max:10',
            'photo_locations.*.latitude' => 'nullable|numeric|between:-90,90', 'photo_locations.*.longitude' => 'nullable|numeric|between:-180,180',
            'photo_locations.*.taken_at' => 'nullable|date_format:Y-m-d H:i:s',
        ], ['photos.required_without' => 'Add at least one photo or video.', 'photos.max' => 'Post up to 10 photos at a time.', 'videos.max' => 'Post up to 4 videos at a time.',
            'videos.*.max' => 'Videos can be up to 50 MB.', 'videos.*.mimetypes' => 'Videos must be MP4, MOV or WebM.']);
        abort_if(count((array) $request->file('videos', [])) !== count((array) $request->file('video_posters', [])), 422, 'Each video needs its still image.');
        $memberId = $this->memberId($request, $trip);
        $post = DB::transaction(function () use ($request, $trip, $memberId) {
            $post = TripPost::create(['trip_id' => $trip->id, 'trip_member_id' => $memberId, 'caption' => trim((string) $request->input('caption')) ?: null]);
            $sent = array_values((array) $request->input('photo_locations', []));
            $photos = array_values((array) $request->file('photos', []));
            foreach ($photos as $position => $file) {
                [$width, $height] = @getimagesize($file->getRealPath()) ?: [null, null];
                $post->photos()->create(['path' => PublicImages::store($file, 'trip-photos'), 'width' => $width, 'height' => $height, 'position' => $position]
                    + $this->whereTaken($file->getRealPath(), $sent[$position] ?? [], $request, $trip));
            }
            $posters = array_values((array) $request->file('video_posters', []));
            $durations = array_values((array) $request->input('video_durations', []));
            foreach (array_values((array) $request->file('videos', [])) as $i => $video) {
                [$width, $height] = @getimagesize($posters[$i]->getRealPath()) ?: [null, null];
                $post->photos()->create(['path' => PublicImages::store($posters[$i], 'trip-photos'), 'video_path' => PublicVideos::store($video),
                    'duration_ms' => isset($durations[$i]) ? (int) $durations[$i] : null, 'width' => $width, 'height' => $height, 'position' => count($photos) + $i]
                    + $this->whereTaken($posters[$i]->getRealPath(), [], $request, $trip));
            }
            return $post;
        });
        try { app(\App\Services\PostPlaces::class)->guess($post, $trip); } catch (\Throwable) { /* the place is a nice extra */ }
        $post->load(['photos', 'member.user:id,name,avatar_path'])->loadCount(['likes', 'comments']);
        $post->liked = false;
        $videos = $post->photos->whereNotNull('video_path')->count(); $images = $post->photos->count() - $videos;
        $what = implode(' and ', array_filter([$images ? ($images === 1 ? 'a photo' : "{$images} photos") : null, $videos ? ($videos === 1 ? 'a video' : "{$videos} videos") : null]));
        app(\App\Services\Alerts::class)->notify($trip, $request->user(), 'post', \App\Services\Alerts::firstName($request->user()).' posted '.$what, $post->caption, ['post_id' => $post->id]);
        return response()->json(['data' => $this->present($post, $trip, $memberId, $request)], 201);
    }

    /** A photo's place and time: what the app read from the photo, else the file's own EXIF, else the phone's location now. */
    private function whereTaken(string $path, mixed $sent, Request $request, Trip $trip): array
    {
        $sent = is_array($sent) ? $sent : [];
        $exif = \App\Support\PhotoMetadata::read($path, $trip->timezone);
        $taken = isset($sent['taken_at']) ? \Carbon\CarbonImmutable::createFromFormat('Y-m-d H:i:s', $sent['taken_at'], $trip->timezone) : $exif['taken_at'];
        // Ignore capture times in the future or long before the trip (wrong camera clock).
        if ($taken && ($taken->isFuture() || $taken->lt(\Carbon\CarbonImmutable::parse($trip->starts_on->format('Y-m-d'), $trip->timezone)->subDays(2)))) { $taken = null; }
        [$lat, $lng, $source] = match (true) {
            isset($sent['latitude'], $sent['longitude']) => [(float) $sent['latitude'], (float) $sent['longitude'], 'photo'],
            $exif['latitude'] !== null => [$exif['latitude'], $exif['longitude'], 'photo'],
            $request->filled(['latitude', 'longitude']) => [(float) $request->input('latitude'), (float) $request->input('longitude'), 'device'],
            default => [null, null, null],
        };
        return ['latitude' => $lat, 'longitude' => $lng, 'location_source' => $source, 'taken_at' => $taken?->utc()];
    }

    public function destroy(Request $request, Trip $trip, TripPost $post)
    {
        abort_unless($post->trip_id === $trip->id, 404);
        Gate::authorize('view', $trip);
        abort_unless($this->canDelete($post, $trip, $this->memberId($request, $trip), $request), 403, 'Only the person who posted this or the organizer can delete it.');
        $paths = $post->photos()->get(['path', 'video_path'])->flatMap(fn ($p) => array_filter([$p->path, $p->video_path]))->all();
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
        // The post's author and everyone already in the conversation hear about it.
        $people = $post->comments()->with('member:id,user_id')->get()->pluck('member.user_id')->push($post->member?->user_id)->filter()->unique()->values()->all();
        $owner = $post->member?->user_id === $request->user()->id ? 'their' : (($post->member?->user?->name ? \App\Services\Alerts::firstName($post->member->user)."’s" : 'a'));
        app(\App\Services\Alerts::class)->notify($trip, $request->user(), 'comment', \App\Services\Alerts::firstName($request->user())." commented on {$owner} photo", $body, ['post_id' => $post->id], $people);
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

    public function video(string $file)
    {
        return PublicVideos::serve($file);
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
            'photos' => $post->photos->map(fn ($photo) => ['url' => PublicImages::url($photo->path), 'width' => $photo->width, 'height' => $photo->height]
                + ($photo->video_path ? ['video_url' => PublicVideos::url($photo->video_path), 'duration_ms' => $photo->duration_ms] : []))->values(),
            'likes_count' => (int) $post->likes_count,
            'comments_count' => (int) ($post->comments_count ?? 0),
            'liked' => (bool) $post->liked,
            'can_delete' => $this->canDelete($post, $trip, $memberId, $request),
            'place' => $post->place_name ? ['name' => $post->place_name, 'itinerary_item_id' => $post->itinerary_item_id] : null,
            'can_edit_place' => $this->canDelete($post, $trip, $memberId, $request),
        ];
    }

    /** Changes the place shown on a post (the poster or the organizer): an itinerary stop, a typed place, or none. */
    public function place(Request $request, Trip $trip, TripPost $post, \App\Services\PostPlaces $places)
    {
        abort_unless($post->trip_id === $trip->id, 404);
        Gate::authorize('view', $trip);
        $memberId = $this->memberId($request, $trip);
        abort_unless($this->canDelete($post, $trip, $memberId, $request), 403, 'Only the person who posted this or the organizer can change its place.');
        $data = $request->validate([
            'itinerary_item_id' => ['nullable', 'integer', \Illuminate\Validation\Rule::exists('itinerary_items', 'id')->where('trip_id', $trip->id)],
            'name' => 'nullable|string|max:200',
            'clear' => 'sometimes|boolean',
        ]);
        abort_unless(! empty($data['clear']) || ! empty($data['itinerary_item_id']) || trim((string) ($data['name'] ?? '')) !== '', 422, 'Choose a stop, type a place, or remove the place.');
        $places->change($post, $trip, $data);
        $post->refresh()->load(['photos', 'member.user:id,name,avatar_path'])->loadCount(['likes', 'comments']);
        $post->liked = $post->likes()->where('trip_member_id', $memberId)->exists();
        return response()->json(['data' => $this->present($post, $trip, $memberId, $request)]);
    }
}
