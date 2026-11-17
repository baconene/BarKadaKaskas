<?php

namespace App\Services\Vertex;

use App\Models\Trip;
use Carbon\CarbonImmutable;
use Illuminate\Support\Facades\{Cache, Log};

/**
 * The planned budget for the trip presentation: from the itinerary (and the cost ranges the AI planner gave stops),
 * Gemini on Vertex AI estimates what each day costs the whole group, by category (food, transport, activities,
 * lodging, other), plus a few notes. Cached per trip and plan, so it is rewritten only when the itinerary changes.
 */
class BudgetEstimator
{
    public const CATEGORIES = ['food', 'transport', 'activities', 'lodging', 'other'];

    public function __construct(private readonly VertexClient $vertex) {}

    /**
     * @return array{available: bool, days: list<array{date: string, items: list<array{label: string, category: string, amount_minor: int}>}>, notes: list<string>}|null
     *         null when the AI couldn't answer
     */
    public function for(Trip $trip): ?array
    {
        if (! $this->vertex->configured()) { return ['available' => false, 'days' => [], 'notes' => []]; }
        $travellers = max(1, $trip->members()->where('status', 'active')->count());
        $items = $trip->itineraryItems()->orderBy('starts_at')->get();
        $start = CarbonImmutable::parse($trip->starts_on->format('Y-m-d')); $end = CarbonImmutable::parse($trip->ends_on->format('Y-m-d'));
        $days = [];
        for ($d = $start; $d->lte($end) && count($days) < 31; $d = $d->addDay()) { $days[$d->format('Y-m-d')] = []; }
        foreach ($items as $i) {
            $date = $i->starts_at->setTimezone($trip->timezone)->format('Y-m-d');
            if (! array_key_exists($date, $days)) { continue; }
            $cost = $i->cost_min_minor !== null ? ' [planned cost '.($i->cost_min_minor / 100).($i->cost_max_minor && $i->cost_max_minor !== $i->cost_min_minor ? '–'.($i->cost_max_minor / 100) : '').' '.$trip->currency_code.($i->cost_note ? ", {$i->cost_note}" : '').']' : '';
            $days[$date][] = $i->starts_at->setTimezone($trip->timezone)->format('g:i A').' '.$i->title.($i->location ? " ({$i->location})" : '').($i->transport_mode ? ", by {$i->transport_mode}" : '').$cost;
        }
        $plan = collect($days)->map(fn ($stops, $date) => $date.': '.($stops ? implode('; ', $stops) : 'free day'))->implode("\n");
        $key = 'budget-estimate:v1:'.$trip->id.':'.md5($plan.$travellers.$trip->currency_code.$trip->destination);
        if (($cached = Cache::get($key)) !== null) { return $cached; }
        $answer = $this->ask($trip, $plan, $travellers, array_keys($days));
        if ($answer) { Cache::put($key, $answer, now()->addDays(30)); }
        return $answer;
    }

    private function ask(Trip $trip, string $plan, int $travellers, array $dates): ?array
    {
        $model = (string) (config('services.gemini.model') ?: 'gemini-2.5-flash');
        $body = [
            'contents' => [['role' => 'user', 'parts' => [['text' => implode("\n", [
                "A group of {$travellers} friends is travelling to {$trip->destination}. Their itinerary, day by day:",
                $plan,
                "Estimate a realistic planned budget for the WHOLE GROUP in {$trip->currency_code}, for each date listed.",
                'For each day give 2 to 6 line items: label (short, e.g. "Lunch at the market", "Habal-habal to the falls", "Entrance fees", "Hotel night"), '
                    .'category (one of: '.implode(', ', self::CATEGORIES).') and amount (a number in '.$trip->currency_code.' for the whole group, no currency sign).',
                'Use the planned costs given for stops when present. Include meals, getting around and a night of lodging when the group sleeps there. Use typical local prices.',
                'notes: 2 to 4 short tips about the budget (what drives cost, where to save, cash needs).',
            ])]]]],
            'generationConfig' => ['temperature' => 0.3, 'responseMimeType' => 'application/json', 'responseSchema' => ['type' => 'object', 'properties' => [
                'days' => ['type' => 'array', 'items' => ['type' => 'object', 'properties' => [
                    'date' => ['type' => 'string'],
                    'items' => ['type' => 'array', 'items' => ['type' => 'object', 'properties' => ['label' => ['type' => 'string'], 'category' => ['type' => 'string', 'enum' => self::CATEGORIES], 'amount' => ['type' => 'number']], 'required' => ['label', 'category', 'amount']]],
                ], 'required' => ['date', 'items']]],
                'notes' => ['type' => 'array', 'items' => ['type' => 'string']],
            ], 'required' => ['days', 'notes']]],
        ];
        $rejectsThinking = 'gemini-rejects-thinking:'.$model;
        try {
            $response = null;
            if (! Cache::get($rejectsThinking)) {
                $response = $this->vertex->generate($model, array_merge_recursive($body, ['generationConfig' => ['thinkingConfig' => ['thinkingBudget' => 0]]]), 40);
                if ($response->status() === 400 && str_contains(strtolower((string) $response->json('error.message')), 'think')) { Cache::forever($rejectsThinking, true); $response = null; }
            }
            $response ??= $this->vertex->generate($model, $body, 40);
        } catch (\Throwable $e) {
            Log::info('Budget estimate unavailable', ['error' => $e->getMessage()]);
            return null;
        }
        if ($response->failed()) { Log::info('Budget estimate failed', ['status' => $response->status(), 'error' => $response->json('error.message')]); return null; }
        $data = json_decode(collect($response->json('candidates.0.content.parts', []))->pluck('text')->filter()->implode(''), true);
        if (! is_array($data) || ! is_array($data['days'] ?? null)) { return null; }
        $days = collect($data['days'])->filter(fn ($d) => is_array($d) && in_array($d['date'] ?? null, $dates, true))->map(fn ($d) => [
            'date' => $d['date'],
            'items' => collect(is_array($d['items'] ?? null) ? $d['items'] : [])->filter(fn ($i) => is_array($i) && is_string($i['label'] ?? null) && is_numeric($i['amount'] ?? null) && $i['amount'] >= 0)
                ->map(fn ($i) => ['label' => mb_substr(trim($i['label']), 0, 80), 'category' => in_array($i['category'] ?? null, self::CATEGORIES, true) ? $i['category'] : 'other', 'amount_minor' => (int) round(min((float) $i['amount'], 100000000) * 100)])
                ->take(8)->values()->all(),
        ])->unique('date')->sortBy('date')->values()->all();
        if (! $days) { return null; }
        $notes = collect(is_array($data['notes'] ?? null) ? $data['notes'] : [])->filter(fn ($n) => is_string($n) && trim($n) !== '')->map(fn ($n) => mb_substr(trim($n), 0, 200))->take(4)->values()->all();
        return ['available' => true, 'days' => $days, 'notes' => $notes];
    }
}
