<?php

namespace App\Http\Controllers;

use App\Models\Trip;
use App\Services\Vertex\BudgetEstimator;
use Carbon\CarbonImmutable;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Gate;

/**
 * The trip presentation: a slide deck of the trip, built from what the group planned and shared. A cover, then for
 * each day its itinerary on a map (the route between the stops, numbered, with some of the day's photos pinned where
 * they were taken) and the day's best photos, then the planned budget (estimated by the AI from the itinerary) next
 * to what was actually spent. The players (web and mobile) render it. The budget comes separately (`budget`), since
 * the AI can take a few seconds.
 */
class PresentationController extends Controller
{
    private const MAX_DAYS = 31, PINS = 6, PHOTOS = 6;
    private string $tz = 'UTC';

    public function show(Request $request, Trip $trip, HighlightController $highlights)
    {
        Gate::authorize('view', $trip);
        @set_time_limit(90);
        $tz = $this->tz = $trip->timezone;
        $start = CarbonImmutable::parse($trip->starts_on->format('Y-m-d')); $end = CarbonImmutable::parse($trip->ends_on->format('Y-m-d'));
        $dayCount = (int) $start->diffInDays($end) + 1;
        $items = $trip->itineraryItems()->orderBy('starts_at')->orderBy('id')->get()->groupBy(fn ($i) => $i->starts_at->setTimezone($tz)->format('Y-m-d'));
        $spent = $this->spentByDay($trip);
        $days = []; $photoTotal = 0; $best = null;
        for ($d = $start, $n = 1; $d->lte($end) && $n <= self::MAX_DAYS; $d = $d->addDay(), $n++) {
            $date = $d->format('Y-m-d');
            $stops = $items[$date] ?? collect();
            [$from, $to] = $highlights->range($trip, $date);
            $photos = $highlights->dayPhotos($trip, $from, $to);
            $photoTotal += $photos->count();
            if ($stops->isEmpty() && $photos->isEmpty()) { continue; }
            $unique = $highlights->distinct($photos->sortByDesc('score')->values(), self::PHOTOS + self::PINS);
            if ($unique->isNotEmpty() && (! $best || $unique->first()['score'] > $best['score'])) { $best = $unique->first(); }

            // The map is drawn by the apps (Google Maps, like the trip's Map screen): each stop with its saved pin (or
            // its address, for the app to look up), and the best photos pinned where they were taken, else at the
            // stop they were posted for, else the stop the group was at when it was taken.
            $numbers = $stops->values()->mapWithKeys(fn ($item, $i) => [$item->id => $i + 1]);
            $pins = $unique->map(function ($p) use ($stops, $numbers) {
                $stopId = $p['stop_id'] ?: ($p['latitude'] === null ? $stops->filter(fn ($s) => $s->starts_at->lte($p['at']))->last()?->id : null);
                $stop = $stopId ? ($numbers[$stopId] ?? null) : null;   // a stop of this day
                if ($p['latitude'] === null && ! $stop) { return null; }
                return ['url' => $p['url'], 'caption' => $p['caption'], 'by' => $p['by'], 'time' => $p['at']->setTimezone($this->tz)->format('g:i A'),
                    'lat' => $p['latitude'] !== null ? (float) $p['latitude'] : null, 'lng' => $p['longitude'] !== null ? (float) $p['longitude'] : null, 'stop' => $stop];
            })->filter()->take(self::PINS)->values()->all();

            $days[] = [
                'date' => $date, 'day_number' => $n, 'label' => 'Day '.$n.' · '.$d->format('D, M j'),
                'route' => $stops->count() > 1 ? $stops->pluck('title')->take(4)->implode(' → ').($stops->count() > 4 ? ' → …' : '') : ($stops->first()?->title ?? $trip->destination),
                'stops' => $stops->values()->map(fn ($item, $i) => [
                    'n' => $i + 1, 'time' => $item->starts_at->setTimezone($tz)->format('g:i A'), 'title' => $item->title, 'location' => $item->location,
                    'transport' => $item->transport_mode, 'done' => $item->completed_at !== null, 'on_map' => $item->latitude !== null || (bool) $item->location,
                    'lat' => $item->latitude !== null ? (float) $item->latitude : null, 'lng' => $item->longitude !== null ? (float) $item->longitude : null,
                ])->all(),
                'pins' => $pins,
                'map' => null,
                'photos' => $unique->take(self::PHOTOS)->map(fn ($p) => ['url' => $p['url'], 'caption' => $p['caption'], 'by' => $p['by'], 'time' => $p['at']->setTimezone($tz)->format('g:i A')])->values()->all(),
                'photo_count' => $photos->count(),
                'spent_minor' => $spent[$date] ?? 0,
            ];
        }
        $travellers = $trip->members()->where('status', 'active')->count();
        $stopCount = $items->flatten()->count();
        return response()->json(['data' => [
            'title' => $trip->name, 'destination' => $trip->destination, 'currency' => $trip->currency_code,
            'dates' => $start->format($start->year === $end->year ? 'M j' : 'M j, Y').' – '.$end->format('M j, Y'),
            'ended' => $trip->isEnded(),
            'cover_url' => $trip->cover_url ?? ($best['url'] ?? null),
            'stats' => [
                ['label' => $dayCount === 1 ? 'day' : 'days', 'value' => (string) $dayCount],
                ['label' => $stopCount === 1 ? 'stop' : 'stops', 'value' => (string) $stopCount],
                ['label' => $photoTotal === 1 ? 'photo' : 'photos', 'value' => (string) $photoTotal],
                ['label' => $travellers === 1 ? 'traveller' : 'travellers', 'value' => (string) $travellers],
            ],
            'spent_minor' => array_sum($spent),
            'days' => $days,
        ]]);
    }

    /** The AI's planned budget per day and category, next to what was actually spent. */
    public function budget(Trip $trip, BudgetEstimator $estimator)
    {
        Gate::authorize('view', $trip);
        @set_time_limit(60);
        $estimate = $estimator->for($trip);
        $spent = $this->spentByDay($trip);
        $start = CarbonImmutable::parse($trip->starts_on->format('Y-m-d'));
        $planned = collect($estimate['days'] ?? [])->keyBy('date');
        $dates = collect(array_keys($spent))->merge($planned->keys())->unique()->sort()->values();
        $days = $dates->map(fn ($date) => [
            'date' => $date, 'label' => 'Day '.((int) $start->diffInDays(CarbonImmutable::parse($date), false) + 1).' · '.CarbonImmutable::parse($date)->format('M j'),
            'estimate_minor' => (int) collect($planned[$date]['items'] ?? [])->sum('amount_minor'),
            'actual_minor' => $spent[$date] ?? 0,
            'items' => $planned[$date]['items'] ?? [],
        ])->all();
        $categories = collect($estimate['days'] ?? [])->flatMap(fn ($d) => $d['items'])->groupBy('category')
            ->map(fn ($g, $c) => ['category' => $c, 'estimate_minor' => (int) $g->sum('amount_minor')])->sortByDesc('estimate_minor')->values()->all();
        $actualCategories = $trip->expenses()->whereIn('status', ['open', 'completed', 'closed'])->with('category:id,name')->get()
            ->groupBy(fn ($e) => $e->category?->name ?? 'Other')->map(fn ($g, $name) => ['name' => $name, 'amount_minor' => (int) $g->sum('total_minor')])->sortByDesc('amount_minor')->values()->all();
        $travellers = max(1, $trip->members()->where('status', 'active')->count());
        $totalEstimate = (int) collect($days)->sum('estimate_minor'); $totalActual = array_sum($spent);
        return response()->json(['data' => [
            'available' => (bool) ($estimate['available'] ?? false),
            'failed' => $estimate === null,
            'currency' => $trip->currency_code,
            'travellers' => $travellers,
            'total_estimate_minor' => $totalEstimate, 'total_actual_minor' => $totalActual,
            'per_person_estimate_minor' => intdiv($totalEstimate, $travellers), 'per_person_actual_minor' => intdiv($totalActual, $travellers),
            'days' => $days, 'categories' => $categories, 'actual_categories' => $actualCategories,
            'notes' => $estimate['notes'] ?? [],
        ]]);
    }

    /** Recorded spending per date (bills that count in balances). @return array<string, int> */
    private function spentByDay(Trip $trip): array
    {
        return $trip->expenses()->whereIn('status', ['open', 'completed', 'closed'])->get(['incurred_at', 'total_minor'])
            ->groupBy(fn ($e) => $e->incurred_at->format('Y-m-d'))->map(fn ($g) => (int) $g->sum('total_minor'))->sortKeys()->all();
    }
}
