<?php

namespace App\Http\Controllers;

use App\Http\Controllers\TravelerProfileController as Ai;
use App\Models\{Trip, TripPostPhoto};
use App\Support\{ImageFingerprint, PublicImages};
use App\Services\Vertex\PhotoRestyler;
use Carbon\CarbonImmutable;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\{Cache, Gate, RateLimiter, Storage};
use Illuminate\Support\Str;

/**
 * A day's highlight (about 30 seconds, story style): a cover, then the day's itinerary stops in order, each with the
 * photos posted around that time, picked by hearts and comments, with near-duplicate shots (the same scenery posted
 * twice, burst shots) left out; music on top. The cover uses the day's best photo, which then isn't repeated in the
 * slides, and carries the trip name, date and the day's key details. The players (web and mobile) render
 * it; the web can also record it as a video file. The cover can be made with the AI image model from the day's best
 * photo; it is kept for everyone on the trip.
 */
class HighlightController extends Controller
{
    private const LENGTH = 30.0; private const COVER = 3.0; private const OUTRO = 2.5; private const MAX_PHOTOS = 12;

    public function show(Request $request, Trip $trip)
    {
        Gate::authorize('view', $trip);
        $date = $this->date($request, $trip);
        [$from, $to] = $this->range($trip, $date);
        $photos = $this->dayPhotos($trip, $from, $to);
        $items = $trip->itineraryItems()->whereBetween('starts_at', [$from, $to])->orderBy('starts_at')->orderBy('id')->get();
        $unique = $this->distinct($photos->sortByDesc('score')->values());
        $coverPhoto = $unique->first();
        // The cover shows the best photo; the slides use the rest (unless it's the only one).
        $picked = ($unique->count() > 1 ? $unique->slice(1) : $unique)->take(self::MAX_PHOTOS);

        // Each photo belongs to the stop that had started when it was posted (or the first stop, if posted earlier).
        $slides = [];
        foreach ($picked->sortBy('posted_at') as $photo) {
            $stop = $items->filter(fn ($item) => $item->starts_at->lte($photo['posted_at']))->last() ?? $items->first();
            $slides[] = [
                'time' => $stop ? $stop->starts_at->setTimezone($trip->timezone)->format('g:i A') : CarbonImmutable::parse($photo['posted_at'])->setTimezone($trip->timezone)->format('g:i A'),
                'title' => $stop?->title ?? ($photo['caption'] ?: $trip->destination),
                'location' => $stop?->location,
                'photo_url' => $photo['url'],
                'caption' => $photo['caption'],
                'stop_id' => $stop?->id,
            ];
        }
        // In itinerary order; photos of the same stop stay in the order they were posted (usort is stable).
        $order = $items->pluck('id')->flip();
        usort($slides, fn ($a, $b) => ($order[$a['stop_id']] ?? PHP_INT_MAX) <=> ($order[$b['stop_id']] ?? PHP_INT_MAX));
        $each = $slides ? max(1.8, (self::LENGTH - self::COVER - self::OUTRO) / count($slides)) : 0;
        $slides = array_map(fn ($s) => $s + ['duration' => round($each, 2)], $slides);

        $start = CarbonImmutable::parse($trip->starts_on->format('Y-m-d'));
        $day = $start->diffInDays(CarbonImmutable::parse($date)) + 1;
        $days = $this->daysWithPhotos($trip);
        return response()->json(['data' => [
            'date' => $date, 'day_number' => (int) $day, 'day_count' => (int) $start->diffInDays(CarbonImmutable::parse($trip->ends_on->format('Y-m-d'))) + 1,
            'title' => $trip->name, 'subtitle' => 'Day '.(int) $day.' · '.CarbonImmutable::parse($date)->format('D, M j'),
            'cover_url' => ($path = Cache::get($this->coverKey($trip, $date))) && Storage::disk('local')->exists($path) ? PublicImages::url($path) : null,
            'base_photo_url' => $coverPhoto['url'] ?? null,
            'cover' => [
                'eyebrow' => 'Day '.(int) $day.' of '.((int) $start->diffInDays(CarbonImmutable::parse($trip->ends_on->format('Y-m-d'))) + 1),
                'title' => $trip->name,
                'date' => CarbonImmutable::parse($date)->format('l, F j, Y'),
                'destination' => $trip->destination,
                'route' => $items->count() > 1 ? $items->first()->title.' → '.$items->last()->title : ($items->first()?->title),
                'stats' => collect([
                    $items->count() ? $items->count().' '.($items->count() === 1 ? 'stop' : 'stops') : null,
                    $photos->count().' '.($photos->count() === 1 ? 'photo' : 'photos'),
                    ($n = $trip->members()->where('status', 'active')->count()).' '.($n === 1 ? 'traveller' : 'travellers'),
                ])->filter()->implode(' · '),
            ],
            'music_url' => asset('audio/highlight-travel.mp3'),
            'cover_duration' => self::COVER, 'outro_duration' => self::OUTRO,
            'slides' => array_values($slides),
            'stops' => $items->map(fn ($i) => ['time' => $i->starts_at->setTimezone($trip->timezone)->format('g:i A'), 'title' => $i->title])->values(),
            'days' => $days,
        ]]);
    }

    /** Makes (or remakes) the day's cover with the AI image model from the day's best photo. Counts as one AI edit. */
    public function cover(Request $request, Trip $trip, PhotoRestyler $restyler)
    {
        Gate::authorize('view', $trip);
        $date = $this->date($request, $trip);
        [$from, $to] = $this->range($trip, $date);
        $best = $this->dayPhotos($trip, $from, $to)->sortByDesc('score')->first();   // the same photo the highlight's cover uses
        abort_unless($best, 422, 'Post a photo on this day first.');
        $key = 'photo-ai:'.$request->user()->id;
        if (RateLimiter::tooManyAttempts($key, Ai::AI_LIMIT)) {
            return response()->json(['message' => 'You’ve used your '.Ai::AI_LIMIT.' AI edits for now. Try again in '.ceil(RateLimiter::availableIn($key) / 60).' min.'], 429);
        }
        @set_time_limit(150);
        try {
            $image = $restyler->restyle((string) Storage::disk('local')->get($best['path']), 'image/jpeg', null,
                'a cinematic opening shot of a travel film: rich film colour grade, warm golden light, gentle vignette and depth, keeping the place and people recognisable', 'photo');
        } catch (\Illuminate\Http\Client\ConnectionException) {
            return response()->json(['message' => 'The AI took too long to answer. Please try again.'], 504);
        } catch (\RuntimeException $e) {
            return response()->json(['message' => $e->getMessage()], in_array($e->getCode(), [422, 502, 503], true) ? $e->getCode() : 502);
        }
        RateLimiter::hit($key, Ai::AI_WINDOW);
        $extension = str_contains($image['mime'], 'jpeg') ? 'jpg' : (str_contains($image['mime'], 'webp') ? 'webp' : 'png');
        $path = 'trip-photos/'.Str::random(40).'.'.$extension;
        Storage::disk('local')->put($path, base64_decode($image['data']));
        if ($old = Cache::get($this->coverKey($trip, $date))) { Storage::disk('local')->delete($old); }
        Cache::forever($this->coverKey($trip, $date), $path);
        return response()->json(['data' => ['cover_url' => PublicImages::url($path)]]);
    }

    private function date(Request $request, Trip $trip): string
    {
        $request->validate(['date' => 'nullable|date_format:Y-m-d']);
        return $request->input('date') ?: CarbonImmutable::now($trip->timezone)->format('Y-m-d');
    }

    /** @return array{0: CarbonImmutable, 1: CarbonImmutable} the day in the trip's time zone, as UTC */
    private function range(Trip $trip, string $date): array
    {
        $start = CarbonImmutable::parse($date, $trip->timezone)->startOfDay();
        return [$start->utc(), $start->endOfDay()->utc()];
    }

    /** Photos posted that day, with a score from the post's hearts and comments. */
    private function dayPhotos(Trip $trip, CarbonImmutable $from, CarbonImmutable $to)
    {
        $posts = $trip->posts()->whereBetween('created_at', [$from, $to])->with('photos')->withCount(['likes', 'comments'])->get();
        return $posts->flatMap(fn ($post) => $post->photos->map(fn (TripPostPhoto $photo) => [
            'url' => PublicImages::url($photo->path), 'path' => $photo->path, 'caption' => $post->caption,
            'posted_at' => $post->created_at, 'score' => $post->likes_count * 2 + $post->comments_count - $photo->position * 0.1,
        ]))->values();
    }

    /** Drops photos that look like one already kept (best first), so the same scenery isn't shown twice. */
    private function distinct($photos)
    {
        $kept = [];
        foreach ($photos as $photo) {
            if (count($kept) > self::MAX_PHOTOS) { break; }
            $print = ImageFingerprint::of($photo['path']);
            if (collect($kept)->contains(fn ($k) => ImageFingerprint::similar($k['print'], $print))) { continue; }
            $kept[] = $photo + ['print' => $print];
        }
        return collect($kept);
    }

    /** Trip days (in the trip's time zone) that have photos, newest first. */
    private function daysWithPhotos(Trip $trip): array
    {
        return $trip->posts()->pluck('created_at')->map(fn ($at) => $at->setTimezone($trip->timezone)->format('Y-m-d'))->unique()->sortDesc()->values()->all();
    }

    private function coverKey(Trip $trip, string $date): string
    {
        return "highlight-cover:{$trip->id}:{$date}";
    }
}
