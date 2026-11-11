<?php

namespace Tests\Feature;

use App\Models\{Trip, User};
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Http;
use Tests\TestCase;

class ItineraryPhotosTest extends TestCase
{
    use RefreshDatabase;

    private function setUpTrip(): array
    {
        $owner = User::factory()->create();
        $trip = Trip::create(['organizer_user_id' => $owner->id, 'name' => 'Siquijor', 'destination' => 'Siquijor', 'starts_on' => '2026-11-11', 'ends_on' => '2026-11-12', 'timezone' => 'Asia/Manila']);
        $trip->members()->create(['user_id' => $owner->id, 'status' => 'active']);
        $item = $trip->itineraryItems()->create(['title' => 'Cambugahay Falls', 'location' => 'Lazi, Siquijor', 'starts_at' => now(), 'ends_at' => now()->addHour(), 'timezone' => 'Asia/Manila', 'created_by_user_id' => $owner->id]);
        return [$owner, $trip, $item];
    }

    public function test_google_photos_come_first_and_wikimedia_tops_them_up(): void
    {
        config(['services.google_maps.browser_key' => 'maps-key']);
        [$owner, $trip, $item] = $this->setUpTrip();
        Http::fake([
            'places.googleapis.com/v1/places:searchText' => Http::response(['places' => [['photos' => [
                ['name' => 'places/abc/photos/1', 'authorAttributions' => [['displayName' => 'Ana']]],
                ['name' => 'places/abc/photos/2', 'authorAttributions' => []],
            ]]]]),
            'places.googleapis.com/v1/places/abc/photos/1/media*' => Http::response(['photoUri' => 'https://lh3.googleusercontent.com/one']),
            'places.googleapis.com/v1/places/abc/photos/2/media*' => Http::response(['photoUri' => 'https://lh3.googleusercontent.com/two']),
            'commons.wikimedia.org/*' => Http::response(['query' => ['pages' => [
                '1' => ['index' => 1, 'imageinfo' => [['mime' => 'image/jpeg', 'url' => 'https://upload.wikimedia.org/a.jpg', 'thumburl' => 'https://upload.wikimedia.org/thumb/a.jpg', 'extmetadata' => ['Artist' => ['value' => '<a href="x">Ben</a>']]]]],
                '2' => ['index' => 2, 'imageinfo' => [['mime' => 'image/svg+xml', 'url' => 'https://upload.wikimedia.org/map.svg']]],
            ]]]),
        ]);
        $photos = $this->actingAs($owner)->getJson("/trips/{$trip->id}/itinerary/{$item->id}/photos")->assertOk()->json('data');
        $this->assertSame(['https://lh3.googleusercontent.com/one', 'https://lh3.googleusercontent.com/two', 'https://upload.wikimedia.org/thumb/a.jpg'], array_column($photos, 'url'));
        $this->assertSame(['Ana', null, 'Ben · Wikimedia Commons'], array_column($photos, 'credit'));
        Http::assertSent(fn ($request) => $request->hasHeader('X-Goog-Api-Key', 'maps-key') && str_contains((string) $request->body(), 'Cambugahay Falls'));
        // Cached: a second look makes no new requests (search + 2 photo links + Wikimedia).
        $this->actingAs($owner)->getJson("/trips/{$trip->id}/itinerary/{$item->id}/photos")->assertOk()->assertJsonCount(3, 'data');
        Http::assertSentCount(4);
    }

    public function test_only_travellers_see_them(): void
    {
        config(['services.google_maps.browser_key' => '']);
        [, $trip, $item] = $this->setUpTrip();
        Http::fake(['commons.wikimedia.org/*' => Http::response(['query' => ['pages' => []]])]);
        $this->actingAs(User::factory()->create())->getJson("/trips/{$trip->id}/itinerary/{$item->id}/photos")->assertForbidden();
    }
}
