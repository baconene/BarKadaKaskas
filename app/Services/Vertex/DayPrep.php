<?php

namespace App\Services\Vertex;

use App\Models\Trip;
use Carbon\CarbonImmutable;
use Illuminate\Support\Facades\{Cache, Log};

/**
 * "Prep for the day": from a day's itinerary, a one-line summary, what to wear, what to bring and a few tips, written
 * by Gemini on Vertex AI. Cached per trip day and itinerary content, so it is rewritten only when that day's plan changes.
 */
class DayPrep
{
    public function __construct(private readonly VertexClient $vertex) {}

    /** @return array{date: string, stops: int, summary: ?string, outfit: list<string>, bring: list<string>, tips: list<string>, available: bool} */
    public function for(Trip $trip, string $date): array
    {
        $start = CarbonImmutable::parse($date, $trip->timezone)->startOfDay();
        $items = $trip->itineraryItems()->whereBetween('starts_at', [$start->utc(), $start->endOfDay()->utc()])->orderBy('starts_at')->get();
        $empty = ['date' => $date, 'stops' => $items->count(), 'summary' => null, 'outfit' => [], 'bring' => [], 'tips' => [], 'available' => $this->vertex->configured()];
        if ($items->isEmpty() || ! $this->vertex->configured()) { return $empty; }
        $plan = $items->map(fn ($i) => $i->starts_at->setTimezone($trip->timezone)->format('g:i A').'–'.$i->ends_at->setTimezone($trip->timezone)->format('g:i A').': '.$i->title
            .($i->location ? " ({$i->location})" : '').($i->transport_mode ? ", getting there by {$i->transport_mode}" : '').($i->description ? '. Notes: '.mb_substr($i->description, 0, 200) : ''))->implode("\n");
        $key = 'day-prep:v1:'.$trip->id.':'.$date.':'.md5($plan);
        if (($cached = Cache::get($key)) !== null) { return $cached + ['stops' => $items->count()]; }
        $answer = $this->ask($trip, $start, $plan);
        $result = $answer ? ['date' => $date, 'stops' => $items->count(), 'available' => true] + $answer : $empty;
        Cache::put($key, $result, $answer ? now()->addDays(30) : now()->addMinutes(10));
        return $result;
    }

    private function ask(Trip $trip, CarbonImmutable $day, string $plan): ?array
    {
        $model = (string) (config('services.gemini.model') ?: 'gemini-2.5-flash');
        $list = ['type' => 'array', 'items' => ['type' => 'string']];
        $body = [
            'contents' => [['role' => 'user', 'parts' => [['text' => implode("\n", [
                "A group of friends is on a trip to {$trip->destination}. Their plan for ".$day->format('l, F j, Y').':',
                $plan,
                'Help them prepare for this day. Be specific to these places and activities (and the usual climate there at this time of year).',
                'summary: one or two sentences describing the day (start, highlights, end).',
                'outfit: 3 to 6 short items of what to wear (e.g. "Swimsuit under quick-dry clothes", "Sandals with straps for the falls").',
                'bring: 4 to 10 short items of things and equipment to bring (e.g. "Dry bag for phones", "Cash in small bills for entrance fees").',
                'tips: 2 to 4 short practical tips for the day (timing, money, safety).',
            ])]]]],
            'generationConfig' => ['temperature' => 0.5, 'responseMimeType' => 'application/json', 'responseSchema' => ['type' => 'object', 'properties' => [
                'summary' => ['type' => 'string'], 'outfit' => $list, 'bring' => $list, 'tips' => $list,
            ], 'required' => ['summary', 'outfit', 'bring', 'tips']]],
        ];
        $rejectsThinking = 'gemini-rejects-thinking:'.$model;
        try {
            $response = null;
            if (! Cache::get($rejectsThinking)) {
                $response = $this->vertex->generate($model, array_merge_recursive($body, ['generationConfig' => ['thinkingConfig' => ['thinkingBudget' => 0]]]), 30);
                if ($response->status() === 400 && str_contains(strtolower((string) $response->json('error.message')), 'think')) { Cache::forever($rejectsThinking, true); $response = null; }
            }
            $response ??= $this->vertex->generate($model, $body, 30);
        } catch (\Throwable $e) {
            Log::info('Day prep unavailable', ['error' => $e->getMessage()]);
            return null;
        }
        if ($response->failed()) { Log::info('Day prep failed', ['status' => $response->status(), 'error' => $response->json('error.message')]); return null; }
        $data = json_decode(collect($response->json('candidates.0.content.parts', []))->pluck('text')->filter()->implode(''), true);
        if (! is_array($data)) { return null; }
        $clean = fn ($list, int $max) => collect(is_array($list) ? $list : [])->filter(fn ($v) => is_string($v) && trim($v) !== '')->map(fn ($v) => mb_substr(trim($v), 0, 160))->take($max)->values()->all();
        $summary = is_string($data['summary'] ?? null) && trim($data['summary']) !== '' ? mb_substr(trim($data['summary']), 0, 500) : null;
        $result = ['summary' => $summary, 'outfit' => $clean($data['outfit'] ?? null, 6), 'bring' => $clean($data['bring'] ?? null, 10), 'tips' => $clean($data['tips'] ?? null, 4)];
        return $summary || $result['outfit'] || $result['bring'] ? $result : null;
    }
}
