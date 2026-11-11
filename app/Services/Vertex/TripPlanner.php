<?php

namespace App\Services\Vertex;

use App\Models\Trip;
use Carbon\CarbonImmutable;
use Illuminate\Http\Client\ConnectionException;
use Illuminate\Support\Facades\{Cache, Log};
use Symfony\Component\HttpKernel\Exception\HttpException;

/**
 * Drafts itinerary changes for a trip with Gemini on Vertex AI: new activities, and new times for activities
 * already planned (to "fix" or rearrange the plan). Nothing is saved here: the organizer reviews the suggestions and
 * approves the ones to apply (see ItineraryPlanController::store). Times are local to the trip
 * ("Y-m-d\TH:i"), costs are per person in minor units, and transport uses the app's own modes.
 */
class TripPlanner
{
    public const TRANSPORT = ['car', 'van', 'taxi', 'motorcycle', 'tricycle', 'walking', 'bicycle', 'transit', 'ferry', 'flight', 'other'];
    private const MAX_ITEMS = 60;

    public function __construct(private readonly VertexClient $vertex) {}

    public function configured(): bool
    {
        return $this->vertex->configured();
    }

    /**
     * Web servers stop waiting after about a minute, so the model gets 50 seconds and a clear message if it is slower.
     *
     * @return array{activities: list<array<string, mixed>>, changes: list<array{item_id: int, title: string, from_starts_at: string, from_ends_at: string, starts_at: string, ends_at: string, reason: ?string}>}
     */
    public function plan(Trip $trip, string $request): array
    {
        if (! $this->configured()) { throw new HttpException(503, 'The AI planner is not set up yet. You can still add activities by hand.'); }
        $model = (string) (config('services.gemini.model') ?: 'gemini-2.5-flash');
        $body = [
            'contents' => [['role' => 'user', 'parts' => [['text' => $this->prompt($trip, $request)]]]],
            'generationConfig' => ['temperature' => 0.6, 'responseMimeType' => 'application/json', 'responseSchema' => $this->schema()],
        ];
        // Same approach as the receipt reader: ask for minimal "thinking" so the answer comes back before web
        // servers time out, and fall back (remembered) when the model rejects that setting.
        $rejectsThinking = 'gemini-rejects-thinking:'.$model;
        try {
            $response = null;
            if (! Cache::get($rejectsThinking)) {
                $response = $this->vertex->generate($model, array_merge_recursive($body, ['generationConfig' => ['thinkingConfig' => ['thinkingBudget' => 0]]]), 50);
                if ($response->status() === 400 && str_contains(strtolower((string) $response->json('error.message')), 'think')) {
                    Cache::forever($rejectsThinking, true);
                    $response = null;
                }
            }
            $response ??= $this->vertex->generate($model, $body, 50);
        } catch (VertexException) {
            throw new HttpException(503, 'The AI planner is misconfigured. Ask the app admin to check the Vertex AI key.');
        } catch (ConnectionException) {
            throw new HttpException(504, 'The AI planner took too long to answer. Try a shorter request, or try again.');
        }
        if ($response->failed()) {
            Log::warning('AI trip planner failed', ['status' => $response->status(), 'error' => $response->json('error.status').': '.$response->json('error.message')]);
            // Say what Google objected to, so a setup problem (model not enabled, quota, region) can be fixed.
            $reason = trim((string) ($response->json('error.status') ?: 'HTTP '.$response->status()).': '.mb_substr((string) $response->json('error.message'), 0, 300), ': ');
            throw new HttpException(502, 'The AI planner is unavailable right now ('.$reason.'). Please try again, or add activities by hand.');
        }
        $text = collect($response->json('candidates.0.content.parts', []))->pluck('text')->filter()->implode('');
        $answer = json_decode($text, true);
        $items = $this->normalize($trip, $answer['activities'] ?? null);
        $changes = $this->changes($trip, $answer['changes'] ?? null);
        if (! $items && ! $changes) { throw new HttpException(422, 'The AI planner could not suggest anything for that. Try describing what you want differently.'); }
        return ['activities' => $items, 'changes' => $changes];
    }

    private function prompt(Trip $trip, string $request): string
    {
        $existing = $trip->itineraryItems()->orderBy('starts_at')->get()
            ->map(fn ($item) => '- #'.$item->id.' '.$item->starts_at->setTimezone($trip->timezone)->format('D Y-m-d H:i').' to '.$item->ends_at->setTimezone($trip->timezone)->format('H:i').': '.$item->title.($item->location ? " ({$item->location})" : '').($item->completed_at ? ' [done]' : ''))->implode("\n");
        return implode("\n", array_filter([
            'You are a travel planner for a group of friends. Help with their itinerary: add activities, or fix and rearrange the plan they already have.',
            "Trip: {$trip->name}. Destination: {$trip->destination}.",
            'Dates: '.$trip->starts_on->format('Y-m-d').' to '.$trip->ends_on->format('Y-m-d')." (time zone {$trip->timezone}). Every activity must fall within these dates.",
            $trip->description ? "About the trip: {$trip->description}" : null,
            $existing !== '' ? "Already planned (each with its #id):\n{$existing}" : null,
            "What the travellers asked for: {$request}",
            'Rules:',
            '- If they ask to fix, move, swap or reschedule planned activities, put those in "changes": the #id as item_id and the new starts_at/ends_at, with a short reason. Also move other activities when needed so nothing overlaps. Never change activities marked [done].',
            '- Only put brand-new places in "activities" (never a copy of a planned one); leave it empty if they only asked to change the plan. New activities must not overlap the plan after your changes.',
            '- Keep it short: only the activities and changes needed for the request.',
            '- Use real, specific places with a searchable address in "location" (place name, town, province/country).',
            '- starts_at and ends_at are local times as YYYY-MM-DDTHH:MM, in visiting order, without overlaps, with realistic travel time between places.',
            '- transport_mode is how they get to this activity from the previous one: one of '.implode(', ', self::TRANSPORT).'; null for the very first activity.',
            "- cost_min and cost_max are the estimated cost per person in {$trip->currency_code} (whole numbers, major units such as pesos); null if free or unknown. cost_note says what the cost covers.",
            '- description: one or two short sentences of useful tips.',
            '- Include arrivals, meals and rest where sensible. At most '.self::MAX_ITEMS.' activities.',
        ]));
    }

    private function schema(): array
    {
        $nullable = fn (string $type) => ['type' => $type, 'nullable' => true];
        return ['type' => 'object', 'properties' => ['activities' => ['type' => 'array', 'items' => ['type' => 'object', 'properties' => [
            'title' => ['type' => 'string'], 'location' => $nullable('string'), 'description' => $nullable('string'),
            'starts_at' => ['type' => 'string'], 'ends_at' => ['type' => 'string'],
            'transport_mode' => ['type' => 'string', 'nullable' => true, 'enum' => self::TRANSPORT],
            'cost_min' => $nullable('number'), 'cost_max' => $nullable('number'), 'cost_note' => $nullable('string'),
        ], 'required' => ['title', 'starts_at', 'ends_at']]],
            'changes' => ['type' => 'array', 'items' => ['type' => 'object', 'properties' => [
                'item_id' => ['type' => 'integer'], 'starts_at' => ['type' => 'string'], 'ends_at' => ['type' => 'string'], 'reason' => $nullable('string'),
            ], 'required' => ['item_id', 'starts_at', 'ends_at']]],
        ], 'required' => ['activities', 'changes']];
    }

    /** New times for planned activities: only real items of this trip, not done, inside the trip dates, actually moved. */
    private function changes(Trip $trip, mixed $changes): array
    {
        if (! is_array($changes)) { return []; }
        $items = $trip->itineraryItems()->whereNull('completed_at')->get()->keyBy('id');
        $first = CarbonImmutable::parse($trip->starts_on->format('Y-m-d'), $trip->timezone)->startOfDay();
        $last = CarbonImmutable::parse($trip->ends_on->format('Y-m-d'), $trip->timezone)->endOfDay();
        $out = [];
        foreach (array_slice($changes, 0, self::MAX_ITEMS) as $change) {
            $item = is_array($change) ? $items->get((int) ($change['item_id'] ?? 0)) : null;
            if (! $item || isset($out[$item->id])) { continue; }
            try {
                $start = CarbonImmutable::parse((string) ($change['starts_at'] ?? ''), $trip->timezone);
                $end = CarbonImmutable::parse((string) ($change['ends_at'] ?? ''), $trip->timezone);
            } catch (\Throwable) { continue; }
            if ($start->lt($first) || $start->gt($last)) { continue; }
            if ($end->lt($start)) { $end = $start->addMinutes(max(30, $item->starts_at->diffInMinutes($item->ends_at))); }
            $from = $item->starts_at->setTimezone($trip->timezone); $fromEnd = $item->ends_at->setTimezone($trip->timezone);
            if ($from->format('Y-m-d\TH:i') === $start->format('Y-m-d\TH:i') && $fromEnd->format('Y-m-d\TH:i') === $end->format('Y-m-d\TH:i')) { continue; }
            $out[$item->id] = [
                'item_id' => $item->id, 'title' => $item->title,
                'from_starts_at' => $from->format('Y-m-d\TH:i'), 'from_ends_at' => $fromEnd->format('Y-m-d\TH:i'),
                'starts_at' => $start->format('Y-m-d\TH:i'), 'ends_at' => $end->format('Y-m-d\TH:i'),
                'reason' => is_string($change['reason'] ?? null) && trim($change['reason']) !== '' ? mb_substr(trim($change['reason']), 0, 300) : null,
            ];
        }
        $out = array_values($out);
        usort($out, fn ($a, $b) => strcmp($a['starts_at'], $b['starts_at']));
        return $out;
    }

    /** Keeps only well-formed activities inside the trip dates, in time order, shaped like the itinerary form. */
    private function normalize(Trip $trip, mixed $activities): array
    {
        if (! is_array($activities)) { return []; }
        $first = CarbonImmutable::parse($trip->starts_on->format('Y-m-d'), $trip->timezone)->startOfDay();
        $last = CarbonImmutable::parse($trip->ends_on->format('Y-m-d'), $trip->timezone)->endOfDay();
        $text = fn ($value, int $max) => is_string($value) && trim($value) !== '' ? mb_substr(trim($value), 0, $max) : null;
        $minor = fn ($value) => is_numeric($value) && $value >= 0 ? (int) min(100000000, round($value * 100)) : null;
        $items = [];
        foreach (array_slice($activities, 0, self::MAX_ITEMS) as $activity) {
            if (! is_array($activity) || ! ($title = $text($activity['title'] ?? null, 200))) { continue; }
            try {
                $start = CarbonImmutable::parse((string) ($activity['starts_at'] ?? ''), $trip->timezone);
                $end = CarbonImmutable::parse((string) ($activity['ends_at'] ?? ''), $trip->timezone);
            } catch (\Throwable) { continue; }
            if ($start->lt($first) || $start->gt($last)) { continue; }
            if ($end->lt($start)) { $end = $start->addHour(); }
            [$min, $max] = [$minor($activity['cost_min'] ?? null), $minor($activity['cost_max'] ?? null)];
            if ($min === null || $max === null) { $min = $max = $min ?? $max; }
            if ($min !== null && $max < $min) { [$min, $max] = [$max, $min]; }
            $mode = in_array($activity['transport_mode'] ?? null, self::TRANSPORT, true) ? $activity['transport_mode'] : null;
            $items[] = [
                'title' => $title, 'location' => $text($activity['location'] ?? null, 200), 'description' => $text($activity['description'] ?? null, 4000),
                'starts_at' => $start->format('Y-m-d\TH:i'), 'ends_at' => $end->format('Y-m-d\TH:i'), 'transport_mode' => $mode,
                'cost_min_minor' => $min, 'cost_max_minor' => $max, 'cost_note' => $min !== null ? $text($activity['cost_note'] ?? null, 255) : null,
            ];
        }
        usort($items, fn ($a, $b) => strcmp($a['starts_at'], $b['starts_at']));
        return $items;
    }
}
