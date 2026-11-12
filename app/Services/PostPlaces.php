<?php

namespace App\Services;

use App\Models\{Trip, TripPost};

/**
 * The place shown on a photo post: guessed when posting from where its photos were taken (the nearest itinerary stop
 * within 1.5 km, else the town), and changeable by the poster or the organizer (pick a stop, type a place, or remove).
 */
class PostPlaces
{
    public function __construct(private readonly HighlightMaps $maps) {}

    public function guess(TripPost $post, Trip $trip): void
    {
        $photo = $post->photos()->whereNotNull('latitude')->orderBy('position')->first();
        if (! $photo) { return; }
        $best = null;
        foreach ($trip->itineraryItems()->get() as $item) {
            $p = $this->maps->position($item, $trip);
            if (! $p) { continue; }
            $d = self::meters($photo->latitude, $photo->longitude, $p[0], $p[1]);
            if ($d <= 1500 && (! $best || $d < $best[1])) { $best = [$item, $d]; }
        }
        $post->update($best
            ? ['place_name' => $best[0]->title, 'itinerary_item_id' => $best[0]->id]
            : ['place_name' => $this->maps->townAt($photo->latitude, $photo->longitude), 'itinerary_item_id' => null]);
    }

    /** @param array{itinerary_item_id?: ?int, name?: ?string, clear?: bool} $choice */
    public function change(TripPost $post, Trip $trip, array $choice): void
    {
        if (! empty($choice['clear'])) {
            $post->update(['place_name' => null, 'itinerary_item_id' => null]);
            $post->photos()->update(['latitude' => null, 'longitude' => null, 'location_source' => null]);
            return;
        }
        if (! empty($choice['itinerary_item_id'])) {
            $item = $trip->itineraryItems()->findOrFail($choice['itinerary_item_id']);
            $post->update(['place_name' => $item->title, 'itinerary_item_id' => $item->id]);
            $point = $this->maps->position($item, $trip);
        } else {
            $name = trim((string) ($choice['name'] ?? ''));
            $post->update(['place_name' => $name, 'itinerary_item_id' => null]);
            $point = $this->maps->geocode(str_contains(mb_strtolower($name), mb_strtolower((string) $trip->destination)) ? $name : $name.', '.$trip->destination);
        }
        if ($point) { $post->photos()->update(['latitude' => $point[0], 'longitude' => $point[1], 'location_source' => 'manual']); }
    }

    public static function meters(float $lat1, float $lng1, float $lat2, float $lng2): float
    {
        $dLat = deg2rad($lat2 - $lat1); $dLng = deg2rad($lng2 - $lng1);
        $a = sin($dLat / 2) ** 2 + cos(deg2rad($lat1)) * cos(deg2rad($lat2)) * sin($dLng / 2) ** 2;
        return 6371000 * 2 * atan2(sqrt($a), sqrt(1 - $a));
    }
}
