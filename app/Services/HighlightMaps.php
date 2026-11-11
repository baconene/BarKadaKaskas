<?php

namespace App\Services;

use App\Models\{ItineraryItem, Trip};
use App\Support\PublicImages;
use Illuminate\Support\Facades\{Cache, Http, Storage};
use Illuminate\Support\Str;

/**
 * Maps for the day highlight's travel scenes: where each stop is (its saved pin, else looked up once with Google's
 * Geocoding API), and a portrait map image covering two stops (Google's Maps Static API), saved like a trip photo so
 * every player (web canvas, phone) can draw the route on it. Both use the Maps key in GOOGLE_MAPS_BROWSER_KEY; without
 * a key, or if Google refuses, the scene simply has no map and the players show the transport card on its own.
 */
class HighlightMaps
{
    /** Image size in map pixels (requested at scale 2, so the file is 720 × 1280, the video's size). */
    public const WIDTH = 360, HEIGHT = 640;

    /** @return array{0: float, 1: float}|null latitude, longitude */
    public function position(ItineraryItem $item, Trip $trip): ?array
    {
        if ($item->latitude !== null && $item->longitude !== null) { return [(float) $item->latitude, (float) $item->longitude]; }
        $key = config('services.google_maps.browser_key');
        $query = trim(($item->location ?: $item->title).', '.$trip->destination, ', ');
        if (! $key || $query === '') { return null; }
        return Cache::rememberForever('geo:v1:'.md5(mb_strtolower($query)), function () use ($key, $query) {
            try {
                $result = Http::connectTimeout(5)->timeout(8)->get('https://maps.googleapis.com/maps/api/geocode/json', ['address' => $query, 'key' => $key])->json('results.0.geometry.location');
            } catch (\Throwable) { return null; }
            return isset($result['lat'], $result['lng']) ? [(float) $result['lat'], (float) $result['lng']] : null;
        }) ?: null;
    }

    /**
     * A map showing both points, with where they fall on the image (0–1 across and down).
     *
     * @return array{url: string, from: array{x: float, y: float}, to: array{x: float, y: float}}|null
     */
    public function route(array $from, array $to): ?array
    {
        $key = config('services.google_maps.browser_key');
        if (! $key) { return null; }
        [$zoom, $center] = $this->frame($from, $to);
        $cacheKey = 'highlight-map:v1:'.md5(json_encode([$zoom, $center]));
        $path = Cache::get($cacheKey);
        if (! $path || ! Storage::disk('local')->exists($path)) {
            try {
                // A calm map so the route stands out: no business pins or transit clutter. ("style" repeats, so the
                // query is built by hand.)
                $query = http_build_query(['center' => $center[0].','.$center[1], 'zoom' => $zoom, 'size' => self::WIDTH.'x'.self::HEIGHT, 'scale' => 2, 'maptype' => 'roadmap', 'key' => $key])
                    .collect(['feature:poi.business|visibility:off', 'feature:transit|visibility:off', 'feature:poi|element:labels.icon|visibility:off'])->map(fn ($s) => '&style='.urlencode($s))->implode('');
                $response = Http::connectTimeout(5)->timeout(15)->get('https://maps.googleapis.com/maps/api/staticmap?'.$query);
            } catch (\Throwable) { return null; }
            if (! $response->ok() || ! str_starts_with((string) $response->header('Content-Type'), 'image/')) { return null; }
            $path = 'trip-photos/'.Str::random(40).'.png';
            Storage::disk('local')->put($path, $response->body());
            Cache::forever($cacheKey, $path);
        }
        return ['url' => PublicImages::url($path), 'from' => $this->point($from, $center, $zoom), 'to' => $this->point($to, $center, $zoom)];
    }

    /** The zoom (and centre) that fits both points with room around them. @return array{0: int, 1: array{0: float, 1: float}} */
    private function frame(array $from, array $to): array
    {
        [$x1, $y1] = $this->world($from); [$x2, $y2] = $this->world($to);
        $center = $this->latLng(($x1 + $x2) / 2, ($y1 + $y2) / 2);
        $dx = max(abs($x1 - $x2), 1e-9); $dy = max(abs($y1 - $y2), 1e-9);
        // World coordinates are 0–256 at zoom 0; leave 30% margins across and 35% down for the captions.
        $zoom = (int) floor(min(log(self::WIDTH * 0.6 / $dx, 2), log(self::HEIGHT * 0.45 / $dy, 2)));
        return [max(3, min(16, $zoom)), [round($center[0], 6), round($center[1], 6)]];
    }

    /** @return array{x: float, y: float} */
    private function point(array $position, array $center, int $zoom): array
    {
        [$x, $y] = $this->world($position); [$cx, $cy] = $this->world($center);
        $scale = 2 ** $zoom;
        return ['x' => round(0.5 + ($x - $cx) * $scale / self::WIDTH, 4), 'y' => round(0.5 + ($y - $cy) * $scale / self::HEIGHT, 4)];
    }

    /** Web Mercator "world" coordinates (0–256). @return array{0: float, 1: float} */
    private function world(array $position): array
    {
        $sin = min(max(sin(deg2rad($position[0])), -0.9999), 0.9999);
        return [256 * (0.5 + $position[1] / 360), 256 * (0.5 - log((1 + $sin) / (1 - $sin)) / (4 * M_PI))];
    }

    /** @return array{0: float, 1: float} */
    private function latLng(float $x, float $y): array
    {
        $n = M_PI - 2 * M_PI * $y / 256;
        return [rad2deg(atan(sinh($n))), ($x / 256 - 0.5) * 360];
    }
}
