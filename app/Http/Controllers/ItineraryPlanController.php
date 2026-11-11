<?php

namespace App\Http\Controllers;

use App\Models\{ActivityLog, Trip};
use App\Services\Vertex\TripPlanner;
use Carbon\CarbonImmutable;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\{DB, Gate};

/**
 * AI-assisted itinerary planning for the organizer: suggest drafts new activities and new times for planned ones
 * (nothing saved); store applies the ones the organizer approved, all at once. Used by the web app and the mobile
 * apps. ("data" stays the list of new activities, so older apps keep working; "changes" holds the moves.)
 */
class ItineraryPlanController extends Controller
{
    public function suggest(Request $request, Trip $trip, TripPlanner $planner)
    {
        Gate::authorize('plan', $trip);
        $data = $request->validate(['prompt' => 'required|string|min:3|max:2000']);
        @set_time_limit(90);
        $plan = $planner->plan($trip, trim($data['prompt']));
        return response()->json(['data' => $plan['activities'], 'changes' => $plan['changes']]);
    }

    public function store(Request $request, Trip $trip)
    {
        Gate::authorize('plan', $trip);
        $data = $request->validate([
            'items' => 'required_without:changes|array|max:60',
            'changes' => 'nullable|array|max:60',
            'changes.*.item_id' => ['required', 'integer', \Illuminate\Validation\Rule::exists('itinerary_items', 'id')->where('trip_id', $trip->id)],
            'changes.*.starts_at' => 'required|date', 'changes.*.ends_at' => 'required|date|after_or_equal:changes.*.starts_at',
            'items.*.title' => 'required|string|max:200', 'items.*.location' => 'nullable|string|max:200', 'items.*.description' => 'nullable|string|max:4000',
            'items.*.transport_mode' => 'nullable|in:'.implode(',', TripPlanner::TRANSPORT),
            'items.*.cost_min_minor' => 'nullable|integer|min:0|max:100000000', 'items.*.cost_max_minor' => 'nullable|integer|min:0|max:100000000|gte:items.*.cost_min_minor',
            'items.*.cost_note' => 'nullable|string|max:255',
            'items.*.starts_at' => 'required|date', 'items.*.ends_at' => 'required|date|after_or_equal:items.*.starts_at',
        ], ['items.required_without' => 'Pick at least one activity or change.']);
        $created = DB::transaction(function () use ($data, $trip, $request) {
            foreach ($data['changes'] ?? [] as $change) {
                $trip->itineraryItems()->whereKey($change['item_id'])->update([
                    'starts_at' => CarbonImmutable::parse($change['starts_at'], $trip->timezone)->utc(),
                    'ends_at' => CarbonImmutable::parse($change['ends_at'], $trip->timezone)->utc(),
                ]);
            }
            $items = collect($data['items'] ?? [])->map(fn ($item) => $trip->itineraryItems()->create([
                'title' => $item['title'], 'location' => $item['location'] ?? null, 'description' => $item['description'] ?? null,
                'transport_mode' => $item['transport_mode'] ?? null,
                // Both bounds or neither, like the itinerary form.
                'cost_min_minor' => isset($item['cost_min_minor'], $item['cost_max_minor']) ? $item['cost_min_minor'] : null,
                'cost_max_minor' => isset($item['cost_min_minor'], $item['cost_max_minor']) ? $item['cost_max_minor'] : null,
                'cost_note' => $item['cost_note'] ?? null,
                'starts_at' => CarbonImmutable::parse($item['starts_at'], $trip->timezone)->utc(),
                'ends_at' => CarbonImmutable::parse($item['ends_at'], $trip->timezone)->utc(),
                'timezone' => $trip->timezone, 'created_by_user_id' => $request->user()->id,
            ]));
            ActivityLog::create(['trip_id' => $trip->id, 'actor_user_id' => $request->user()->id, 'subject_type' => 'trip', 'subject_id' => $trip->id, 'action' => 'itinerary_planned', 'after' => ['count' => $items->count(), 'ids' => $items->pluck('id')->all(), 'moved' => array_column($data['changes'] ?? [], 'item_id')]]);
            return $items;
        });
        $moved = count($data['changes'] ?? []);
        $what = array_filter([
            $created->count() ? 'added '.$created->count().' '.($created->count() === 1 ? 'activity' : 'activities') : null,
            $moved ? 'moved '.$moved.' '.($moved === 1 ? 'activity' : 'activities') : null,
        ]);
        app(\App\Services\Alerts::class)->notify($trip, $request->user(), 'itinerary', 'Itinerary updated',
            \App\Services\Alerts::firstName($request->user()).' '.implode(' and ', $what).' in the plan');
        return response()->json(['data' => $created->values()], 201);
    }
}
