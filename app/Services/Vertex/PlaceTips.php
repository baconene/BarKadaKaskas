<?php

namespace App\Services\Vertex;

use App\Models\{ItineraryItem, Trip};
use Illuminate\Support\Facades\{Cache, Log};

/**
 * Recommended things to do at an itinerary stop's place (about five, each with a one-line tip), written by Gemini on
 * Vertex AI and cached per place for a month, so each place costs one short request however often it is opened.
 */
class PlaceTips
{
    public function __construct(private readonly VertexClient $vertex) {}

    /** @return list<array{title: string, detail: ?string}> empty when the AI isn't set up or can't answer */
    public function for(ItineraryItem $item, Trip $trip): array
    {
        if (! $this->vertex->configured() || trim($item->title) === '') { return []; }
        $place = implode(', ', array_values(array_unique(array_filter([trim($item->title), trim((string) $item->location), trim((string) $trip->destination)]))));
        $key = 'place-tips:v1:'.md5(mb_strtolower($place));
        if (($cached = Cache::get($key)) !== null) { return $cached; }
        $tips = $this->ask($place);
        // Remember answers for a month; a failure only briefly, so it is retried soon.
        Cache::put($key, $tips, $tips ? now()->addDays(30) : now()->addMinutes(10));
        return $tips;
    }

    private function ask(string $place): array
    {
        $model = (string) (config('services.gemini.model') ?: 'gemini-2.5-flash');
        $body = [
            'contents' => [['role' => 'user', 'parts' => [['text' => implode("\n", [
                "Place on a group trip's itinerary: {$place}.",
                'List 5 recommended things to do at this place for a group of friends: specific to this place (not generic travel advice), practical and safe.',
                'title: a short action (at most 8 words). detail: one sentence with a useful tip (best time, what to bring, cost or where exactly).',
                'If the place is a meal stop, hotel, port or airport, recommend what to order, try or do there.',
            ])]]]],
            'generationConfig' => ['temperature' => 0.5, 'responseMimeType' => 'application/json', 'responseSchema' => ['type' => 'object', 'properties' => ['activities' => ['type' => 'array', 'items' => ['type' => 'object', 'properties' => [
                'title' => ['type' => 'string'], 'detail' => ['type' => 'string', 'nullable' => true],
            ], 'required' => ['title']]]], 'required' => ['activities']]],
        ];
        $rejectsThinking = 'gemini-rejects-thinking:'.$model;
        try {
            $response = null;
            if (! Cache::get($rejectsThinking)) {
                $response = $this->vertex->generate($model, array_merge_recursive($body, ['generationConfig' => ['thinkingConfig' => ['thinkingBudget' => 0]]]), 25);
                if ($response->status() === 400 && str_contains(strtolower((string) $response->json('error.message')), 'think')) { Cache::forever($rejectsThinking, true); $response = null; }
            }
            $response ??= $this->vertex->generate($model, $body, 25);
        } catch (\Throwable $e) {
            Log::info('Place tips unavailable', ['error' => $e->getMessage()]);
            return [];
        }
        if ($response->failed()) { Log::info('Place tips failed', ['status' => $response->status(), 'error' => $response->json('error.message')]); return []; }
        $text = collect($response->json('candidates.0.content.parts', []))->pluck('text')->filter()->implode('');
        return collect(json_decode($text, true)['activities'] ?? [])
            ->filter(fn ($a) => is_array($a) && is_string($a['title'] ?? null) && trim($a['title']) !== '')
            ->map(fn ($a) => ['title' => mb_substr(trim($a['title']), 0, 80), 'detail' => is_string($a['detail'] ?? null) && trim($a['detail']) !== '' ? mb_substr(trim($a['detail']), 0, 240) : null])
            ->take(6)->values()->all();
    }
}
