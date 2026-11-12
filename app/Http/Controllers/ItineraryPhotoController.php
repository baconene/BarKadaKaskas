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
        @set_time_limit(50);
        return response()->json(['data' => $prep->for($trip, $request->input('date') ?: \Carbon\CarbonImmutable::now($trip->timezone)->format('Y-m-d'))]);
    }

    public function tips(Trip $trip, ItineraryItem $item, PlaceTips $tips)
    {
        Gate::authorize('view', $trip);
        abort_unless($item->trip_id === $trip->id, 404);
        @set_time_limit(45);
        return response()->json(['data' => $tips->for($item, $trip)]);
    }
}
