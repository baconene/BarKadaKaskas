<?php

namespace App\Services;

use App\Models\{ItineraryItem, Trip};
use Illuminate\Support\Facades\{Cache, Http, Log};

/**
 * Photos of an itinerary stop's place from the web, for the activity details: a couple of Google Places photos first
 * (Places API (New), with the Maps key in GOOGLE_MAPS_BROWSER_KEY), topped up from Wikimedia Commons, which is free.
 * Each Google photo is billed separately, so only GOOGLE_PHOTOS are fetched per place. A new place looked up on Google
 * uses one of the viewer's monthly "place_photos" allowance; once it's used up, the viewer gets Wikimedia photos only.
 * Only links are kept (no files are copied), with the credit each source asks for, cached for a week per place.
 */
class PlacePhotos
{
    public const WANT = 6;
    public const GOOGLE_PHOTOS = 2;
    private const AGENT = 'BarkadaKasKas/1.0 (group trip planner)';

    /** @return list<array{url: string, credit: ?string, source: string}> */
    public function for(ItineraryItem $item, Trip $trip, ?\App\Models\User $viewer = null): array
    {
        // The place's name plus where it is ("Cambugahay Falls, Lazi, Siquijor"), adding the destination when missing.
        $parts = array_values(array_unique(array_filter([trim($item->title), trim((string) $item->location)])));
        if (! $parts) { return []; }
        $query = implode(', ', $parts);
        if ($trip->destination && ! str_contains(mb_strtolower($query), mb_strtolower($trip->destination))) { $query .= ', '.$trip->destination; }
        $key = 'place-photos:v2:'.md5(mb_strtolower($query));
        if (($cached = Cache::get($key)) !== null) { return $cached; }
        $free = fn () => Cache::remember($key.':free', now()->addDays(7), fn () => array_slice($this->wikimedia($item->title, $query, self::WANT), 0, self::WANT));
        // No allowance left (or no Google key): Wikimedia only, cached on its own so Google can still be tried later.
        if (! config('services.google_maps.browser_key') || ($viewer && ! \App\Support\Usage::allows($viewer, 'place_photos'))) { return $free(); }
        $google = $this->google($query, $item);
        if ($viewer) { \App\Support\Usage::record($viewer, 'place_photos'); }
        $photos = array_slice([...$google, ...$this->wikimedia($item->title, $query, self::WANT - count($google))], 0, self::WANT);
        Cache::put($key, $photos, now()->addDays(7));
        return $photos;
    }

    private function google(string $query, ItineraryItem $item): array
    {
        $key = config('services.google_maps.browser_key');
        if (! $key) { return []; }
        try {
            // Text Search Pro: only the photo references are asked for.
            $body = ['textQuery' => $query, 'maxResultCount' => 1];
            if ($item->latitude !== null && $item->longitude !== null) {
                $body['locationBias'] = ['circle' => ['center' => ['latitude' => (float) $item->latitude, 'longitude' => (float) $item->longitude], 'radius' => 2000.0]];
            }
            $found = Http::connectTimeout(5)->timeout(10)->withHeaders(['X-Goog-Api-Key' => $key, 'X-Goog-FieldMask' => 'places.photos'])
                ->post('https://places.googleapis.com/v1/places:searchText', $body);
            if (! $found->ok()) { Log::info('Place photos: Google search failed', ['status' => $found->status(), 'error' => $found->json('error.message')]); return []; }
            $photos = [];
            foreach (array_slice($found->json('places.0.photos', []), 0, self::GOOGLE_PHOTOS) as $photo) {
                // The media endpoint returns a short Google-hosted link to the image, so the key never reaches the browser.
                $media = Http::connectTimeout(5)->timeout(8)->get('https://places.googleapis.com/v1/'.$photo['name'].'/media', ['maxWidthPx' => 1200, 'skipHttpRedirect' => 'true', 'key' => $key]);
                if ($uri = $media->json('photoUri')) {
                    $photos[] = ['url' => $uri, 'credit' => $photo['authorAttributions'][0]['displayName'] ?? null, 'source' => 'Google'];
                }
            }
            return $photos;
        } catch (\Throwable $e) {
            Log::info('Place photos: Google unavailable', ['error' => $e->getMessage()]);
            return [];
        }
    }

    private function wikimedia(string $title, string $query, int $count): array
    {
        if ($count <= 0) { return []; }
        foreach (array_unique([$query, $title]) as $search) {
            try {
                $pages = Http::connectTimeout(5)->timeout(10)->withUserAgent(self::AGENT)->get('https://commons.wikimedia.org/w/api.php', [
                    'action' => 'query', 'format' => 'json', 'generator' => 'search', 'gsrsearch' => 'filetype:bitmap '.$search, 'gsrnamespace' => 6,
                    'gsrlimit' => $count + 4, 'prop' => 'imageinfo', 'iiprop' => 'url|extmetadata|mime', 'iiurlwidth' => 1200,
                ])->json('query.pages', []);
            } catch (\Throwable) { return []; }
            $photos = collect($pages)->sortBy('index')->map(function ($page) {
                $info = $page['imageinfo'][0] ?? null;
                if (! $info || ! in_array($info['mime'] ?? '', ['image/jpeg', 'image/png', 'image/webp'], true)) { return null; }
                $artist = trim(html_entity_decode(strip_tags((string) ($info['extmetadata']['Artist']['value'] ?? ''))));
                return ['url' => $info['thumburl'] ?? $info['url'], 'credit' => ($artist !== '' ? mb_substr($artist, 0, 80).' · ' : '').'Wikimedia Commons', 'source' => 'Wikimedia Commons'];
            })->filter()->take($count)->values()->all();
            if ($photos) { return $photos; }
        }
        return [];
    }
}
