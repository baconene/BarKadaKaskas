<?php

namespace App\Http\Controllers;

use App\Models\{ActivityLog, Trip};
use App\Services\Vertex\TripPlanner;
use Carbon\CarbonImmutable;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\{DB, Gate};

/**
 * AI-assisted itinerary planning for the organizer: suggest drafts activities (nothing saved), store saves the
 * ones the organizer approved, all at once. Used by the web app and the mobile apps.
 */
class ItineraryPlanController extends Controller
{
    public function suggest(Request $request, Trip $trip, TripPlanner $planner)
    {
        Gate::authorize('plan', $trip);
        $data = $request->validate(['prompt' => 'required|string|min:3|max:2000']);
        return response()->json(['data' => $planner->plan($trip, trim($data['prompt']))]);
    }

    public function store(Request $request, Trip $trip)
    {
        Gate::authorize('plan', $trip);
        $data = $request->validate([
            'items' => 'required|array|min:1|max:60',
            'items.*.title' => 'required|string|max:200', 'items.*.location' => 'nullable|string|max:200', 'items.*.description' => 'nullable|string|max:4000',
            'items.*.transport_mode' => 'nullable|in:'.implode(',', TripPlanner::TRANSPORT),
            'items.*.cost_min_minor' => 'nullable|integer|min:0|max:100000000', 'items.*.cost_max_minor' => 'nullable|integer|min:0|max:100000000|gte:items.*.cost_min_minor',
            'items.*.cost_note' => 'nullable|string|max:255',
            'items.*.starts_at' => 'required|date', 'items.*.ends_at' => 'required|date|after_or_equal:items.*.starts_at',
        ], ['items.required' => 'Pick at least one activity to add.']);
        $created = DB::transaction(function () use ($data, $trip, $request) {
            $items = collect($data['items'])->map(fn ($item) => $trip->itineraryItems()->create([
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
            ActivityLog::create(['trip_id' => $trip->id, 'actor_user_id' => $request->user()->id, 'subject_type' => 'trip', 'subject_id' => $trip->id, 'action' => 'itinerary_planned', 'after' => ['count' => $items->count(), 'ids' => $items->pluck('id')->all()]]);
            return $items;
        });
        return response()->json(['data' => $created->values()], 201);
    }
}
