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
        return $this->geocode(trim(($item->location ?: $item->title).', '.$trip->destination, ', '));
    }

    /** Coordinates for a place name or address (Google Geocoding, cached). @return array{0: float, 1: float}|null */
    public function geocode(string $query): ?array
    {
        $key = config('services.google_maps.browser_key');
        if (! $key || trim($query) === '') { return null; }
        return Cache::rememberForever('geo:v1:'.md5(mb_strtolower($query)), function () use ($key, $query) {
            try {
                $result = Http::connectTimeout(5)->timeout(8)->get('https://maps.googleapis.com/maps/api/geocode/json', ['address' => $query, 'key' => $key])->json('results.0.geometry.location');
            } catch (\Throwable) { return null; }
            return isset($result['lat'], $result['lng']) ? [(float) $result['lat'], (float) $result['lng']] : null;
        }) ?: null;
    }

    /** The town and province at a point, e.g. "San Juan, Siquijor" (Google reverse geocoding, cached). */
    public function townAt(float $lat, float $lng): ?string
    {
        $key = config('services.google_maps.browser_key');
        if (! $key) { return null; }
        return Cache::rememberForever('town:v1:'.round($lat, 3).','.round($lng, 3), function () use ($key, $lat, $lng) {
            try {
                $parts = Http::connectTimeout(5)->timeout(8)->get('https://maps.googleapis.com/maps/api/geocode/json', ['latlng' => "{$lat},{$lng}", 'key' => $key])->json('results.0.address_components', []);
            } catch (\Throwable) { return null; }
            $pick = fn (string $type) => collect($parts)->first(fn ($c) => in_array($type, $c['types'] ?? [], true))['long_name'] ?? null;
            $town = $pick('locality') ?? $pick('administrative_area_level_3');
            $area = $pick('administrative_area_level_2') ?? $pick('administrative_area_level_1');
            return implode(', ', array_values(array_unique(array_filter([$town, $area])))) ?: null;
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

    /**
     * A landscape map of a day for the trip presentation: the route between the day's stops (following the roads when
     * Google's Directions API answers, else straight lines; boat and plane legs are always straight), numbered stop
     * markers, and where each stop and each extra point (photo pins) falls on the image (0–1 across and down) so the
     * players can draw on it. Null without a Maps key or when Google refuses.
     *
     * @param  list<array{0: float, 1: float}>  $stops  in visiting order
     * @param  list<?string>  $modes  transport to reach each stop (index-aligned with $stops; the first is unused)
     * @param  list<array{0: float, 1: float}>  $extra  more points the map must show (photo pins)
     * @param  list<int>  $labels  the number shown on each stop's marker (defaults to 1, 2, 3…)
     * @return array{url: string, stops: list<array{x: float, y: float}>, extra: list<array{x: float, y: float}>}|null
     */
    public function dayMap(array $stops, array $modes = [], array $extra = [], array $labels = []): ?array
    {
        $key = config('services.google_maps.browser_key');
        $all = [...$stops, ...$extra];
        if (! $key || ! $all) { return null; }
        [$width, $height] = [640, 400];
        [$zoom, $center] = $this->frameAll($all, $width, $height);
        $paths = [];
        for ($i = 1; $i < count($stops); $i++) {
            if ($stops[$i] === $stops[$i - 1]) { continue; }
            $road = in_array($modes[$i] ?? null, ['ferry', 'flight'], true) ? null : $this->directions($stops[$i - 1], $stops[$i], ($modes[$i] ?? null) === 'walking' ? 'walking' : 'driving');
            $paths[] = 'color:0x214e3edd|weight:5|'.($road ? 'enc:'.$road : $stops[$i - 1][0].','.$stops[$i - 1][1].'|'.$stops[$i][0].','.$stops[$i][1]);
        }
        // Marker labels are one character: 1–9, then A, B, C…
        $markers = collect($stops)->map(fn ($p, $i) => 'size:mid|color:0xd9643a|label:'.(($n = $labels[$i] ?? $i + 1) <= 9 ? $n : chr(min(90, 55 + $n))).'|'.$p[0].','.$p[1])->all();
        $cacheKey = 'day-map:v1:'.md5(json_encode([$zoom, $center, $paths, $markers]));
        $path = Cache::get($cacheKey);
        if (! $path || ! Storage::disk('local')->exists($path)) {
            $style = collect(['feature:poi.business|visibility:off', 'feature:transit|visibility:off', 'feature:poi|element:labels.icon|visibility:off'])->map(fn ($s) => '&style='.urlencode($s))->implode('');
            $build = fn (array $paths) => 'https://maps.googleapis.com/maps/api/staticmap?'.http_build_query(['center' => $center[0].','.$center[1], 'zoom' => $zoom, 'size' => "{$width}x{$height}", 'scale' => 2, 'maptype' => 'roadmap', 'key' => $key])
                .$style.collect($paths)->map(fn ($p) => '&path='.urlencode($p))->implode('').collect($markers)->map(fn ($m) => '&markers='.urlencode($m))->implode('');
            $url = $build($paths);
            // Google limits URLs to 16 384 characters: fall back to straight lines for long road routes.
            if (strlen($url) > 16000) { $url = $build(count($stops) > 1 ? ['color:0x214e3edd|weight:5|'.collect($stops)->map(fn ($p) => $p[0].','.$p[1])->implode('|')] : []); }
            try { $response = Http::connectTimeout(5)->timeout(15)->get($url); } catch (\Throwable) { return null; }
            if (! $response->ok() || ! str_starts_with((string) $response->header('Content-Type'), 'image/')) { return null; }
            $path = 'trip-photos/'.Str::random(40).'.png';
            Storage::disk('local')->put($path, $response->body());
            Cache::forever($cacheKey, $path);
        }
        $place = fn ($p) => $this->point($p, $center, $zoom, $width, $height);
        return ['url' => PublicImages::url($path), 'stops' => array_map($place, $stops), 'extra' => array_map($place, $extra)];
    }

    /** The road route between two points as an encoded polyline (Google Directions, cached), or null. */
    private function directions(array $from, array $to, string $mode): ?string
    {
        $key = config('services.google_maps.browser_key');
        return Cache::rememberForever('directions:v1:'.md5(json_encode([$from, $to, $mode])), function () use ($key, $from, $to, $mode) {
            try {
                $route = Http::connectTimeout(5)->timeout(8)->get('https://maps.googleapis.com/maps/api/directions/json', ['origin' => "{$from[0]},{$from[1]}", 'destination' => "{$to[0]},{$to[1]}", 'mode' => $mode, 'key' => $key])
                    ->json('routes.0.overview_polyline.points');
            } catch (\Throwable) { return ''; }
            return is_string($route) ? $route : '';
        }) ?: null;
    }

    /** The zoom and centre that fit all points in a width × height map, with margins. @return array{0: int, 1: array{0: float, 1: float}} */
    private function frameAll(array $points, int $width, int $height): array
    {
        $world = array_map(fn ($p) => $this->world($p), $points);
        $xs = array_column($world, 0); $ys = array_column($world, 1);
        $center = $this->latLng((min($xs) + max($xs)) / 2, (min($ys) + max($ys)) / 2);
        $dx = max(max($xs) - min($xs), 1e-9); $dy = max(max($ys) - min($ys), 1e-9);
        $zoom = (int) floor(min(log($width * 0.8 / $dx, 2), log($height * 0.75 / $dy, 2)));
        return [max(3, min(15, $zoom)), [round($center[0], 6), round($center[1], 6)]];
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
    private function point(array $position, array $center, int $zoom, int $width = self::WIDTH, int $height = self::HEIGHT): array
    {
        [$x, $y] = $this->world($position); [$cx, $cy] = $this->world($center);
        $scale = 2 ** $zoom;
        return ['x' => round(0.5 + ($x - $cx) * $scale / $width, 4), 'y' => round(0.5 + ($y - $cy) * $scale / $height, 4)];
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
