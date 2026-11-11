<?php

namespace App\Services;

use App\Models\{ItineraryItem, Trip};
use Illuminate\Support\Facades\{Cache, Http, Log};

/**
 * Photos of an itinerary stop's place from the web, for the activity details: Google Places photos first (Places API
 * (New), with the Maps key in GOOGLE_MAPS_BROWSER_KEY), topped up from Wikimedia Commons when Google has fewer than
 * five or isn't set up. Only links are kept (no files are copied), with the credit each source asks for, cached for a
 * week per place.
 */
class PlacePhotos
{
    public const WANT = 6;
    private const AGENT = 'BarkadaKasKas/1.0 (group trip planner)';

    /** @return list<array{url: string, credit: ?string, source: string}> */
    public function for(ItineraryItem $item, Trip $trip): array
    {
        $place = trim($item->location ?: $item->title);
        if ($place === '') { return []; }
        $query = str_contains(mb_strtolower($place), mb_strtolower((string) $trip->destination)) ? $place : trim($item->title.', '.$place.', '.$trip->destination, ', ');
        return Cache::remember('place-photos:v1:'.md5(mb_strtolower($query)), now()->addDays(7), function () use ($query, $item) {
            $photos = $this->google($query, $item);
            if (count($photos) < 5) { $photos = [...$photos, ...$this->wikimedia($item->title, $query, self::WANT - count($photos))]; }
            return array_slice($photos, 0, self::WANT);
        });
    }

    private function google(string $query, ItineraryItem $item): array
    {
        $key = config('services.google_maps.browser_key');
        if (! $key) { return []; }
        try {
            $body = ['textQuery' => $query, 'maxResultCount' => 1];
            if ($item->latitude !== null && $item->longitude !== null) {
                $body['locationBias'] = ['circle' => ['center' => ['latitude' => (float) $item->latitude, 'longitude' => (float) $item->longitude], 'radius' => 2000.0]];
            }
            $found = Http::connectTimeout(5)->timeout(10)->withHeaders(['X-Goog-Api-Key' => $key, 'X-Goog-FieldMask' => 'places.photos'])
                ->post('https://places.googleapis.com/v1/places:searchText', $body);
            if (! $found->ok()) { Log::info('Place photos: Google search failed', ['status' => $found->status(), 'error' => $found->json('error.message')]); return []; }
            $photos = [];
            foreach (array_slice($found->json('places.0.photos', []), 0, self::WANT) as $photo) {
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
