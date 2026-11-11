<?php

namespace App\Http\Controllers;

use App\Models\{ItineraryItem, Trip};
use App\Services\PlacePhotos;
use Illuminate\Support\Facades\Gate;

/** Photos of a stop's place from the web (see PlacePhotos), shown when its details are opened. */
class ItineraryPhotoController extends Controller
{
    public function show(Trip $trip, ItineraryItem $item, PlacePhotos $photos)
    {
        Gate::authorize('view', $trip);
        abort_unless($item->trip_id === $trip->id, 404);
        @set_time_limit(60);
        return response()->json(['data' => $photos->for($item, $trip)]);
    }
}
