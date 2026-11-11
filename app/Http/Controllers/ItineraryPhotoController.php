<?php

namespace App\Http\Controllers;

use App\Models\{ItineraryItem, Trip};
use App\Services\PlacePhotos;
use App\Services\Vertex\PlaceTips;
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

    public function tips(Trip $trip, ItineraryItem $item, PlaceTips $tips)
    {
        Gate::authorize('view', $trip);
        abort_unless($item->trip_id === $trip->id, 404);
        @set_time_limit(45);
        return response()->json(['data' => $tips->for($item, $trip)]);
    }
}
