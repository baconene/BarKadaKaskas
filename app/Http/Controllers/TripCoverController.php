<?php

namespace App\Http\Controllers;

use App\Models\Trip;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\{Gate, Storage};
use Illuminate\Support\Str;

/**
 * Organizer-uploaded trip cover photos. Files live on the private disk under a random 40-character
 * name and are served by that name without a session, so the mobile apps can load them like any
 * image URL. The name changes on every upload, so responses can be cached for a long time.
 */
class TripCoverController extends Controller
{
    public const DIRECTORY = 'trip-covers';

    public function store(Request $request, Trip $trip)
    {
        Gate::authorize('plan', $trip);
        $request->validate(['cover' => 'required|image|mimes:jpg,jpeg,png,webp|max:8192|dimensions:min_width=200,min_height=120']);
        $file = $request->file('cover');
        $extension = match ($file->getMimeType()) { 'image/png' => 'png', 'image/webp' => 'webp', default => 'jpg' };
        $path = $file->storeAs(self::DIRECTORY, Str::random(40).'.'.$extension, 'local');
        $previous = $trip->cover_path;
        $trip->update(['cover_path' => $path]);
        if ($previous) { Storage::disk('local')->delete($previous); }
        return $request->is('api/*') ? response()->json(['data' => $trip->fresh()]) : back();
    }

    public function destroy(Request $request, Trip $trip)
    {
        Gate::authorize('plan', $trip);
        if ($trip->cover_path) { Storage::disk('local')->delete($trip->cover_path); }
        $trip->update(['cover_path' => null]);
        return $request->is('api/*') ? response()->json(['data' => $trip->fresh()]) : back();
    }

    public function show(string $file)
    {
        abort_unless(preg_match('/^[A-Za-z0-9]{40}\.(jpg|png|webp)$/', $file) === 1, 404);
        $path = self::DIRECTORY.'/'.$file;
        abort_unless(Storage::disk('local')->exists($path), 404);
        return Storage::disk('local')->response($path, null, ['Cache-Control' => 'public, max-age=31536000, immutable', 'X-Content-Type-Options' => 'nosniff']);
    }
}
