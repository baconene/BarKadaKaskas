<?php

namespace App\Http\Controllers;

use App\Models\{ItineraryItem, Trip};
use App\Services\PlacePhotos;
use App\Services\Vertex\{DayPrep, PlaceTips};
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Gate;

/** Photos of a stop's place from the web (PlacePhotos) and recommended things to do there (PlaceTips), shown when its details are opened. */
class ItineraryPhotoController extends Controller
{
    public function show(Trip $trip, ItineraryItem $item, PlacePhotos $photos)
    {
        Gate::authorize('view', $trip);
        abort_unless($item->trip_id === $trip->id, 404);
        @set_time_limit(60);
        return response()->json(['data' => $photos->for($item, $trip)]);
    }

    /** "Prep for the day": summary, what to wear and what to bring for a day of the itinerary (?date=Y-m-d, default today). */
    public function day(Request $request, Trip $trip, DayPrep $prep)
    {
        Gate::authorize('view', $trip);
        $request->validate(['date' => 'nullable|date_format:Y-m-d']);
        $date = $request->input('date') ?: \Carbon\CarbonImmutable::now($trip->timezone)->format('Y-m-d');
        // AI: only for the trip's organizer with Premium; others get the "not available" answer.
        if (! \App\Support\Premium::allows($request->user(), $trip)) {
            return response()->json(['data' => ['date' => $date, 'stops' => 0, 'summary' => null, 'outfit' => [], 'bring' => [], 'tips' => [], 'available' => false, 'premium_required' => true]]);
        }
        @set_time_limit(50);
        return response()->json(['data' => $prep->for($trip, $date)]);
    }

    public function tips(Request $request, Trip $trip, ItineraryItem $item, PlaceTips $tips)
    {
        Gate::authorize('view', $trip);
        abort_unless($item->trip_id === $trip->id, 404);
        if (! \App\Support\Premium::allows($request->user(), $trip)) { return response()->json(['data' => [], 'premium_required' => true]); }
        @set_time_limit(45);
        return response()->json(['data' => $tips->for($item, $trip)]);
    }
}
