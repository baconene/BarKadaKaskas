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

    public function test_recommended_activities_come_from_the_ai_and_are_cached(): void
    {
        config(['services.gemini.api_key' => 'test-key', 'services.gemini.credentials' => null, 'services.gemini.project' => 'demo', 'services.gemini.model' => 'gemini-2.5-flash']);
        [$owner, $trip, $item] = $this->setUpTrip();
        Http::fake(['aiplatform.googleapis.com/*' => Http::response(['candidates' => [['content' => ['parts' => [['text' => json_encode(['activities' => [
            ['title' => 'Swing from the rope into the pool', 'detail' => 'Go before 9 AM to beat the crowds.'],
            ['title' => '  ', 'detail' => 'dropped'],
            ['title' => 'Ride the bamboo raft'],
        ]])]]]]]])]);
        $url = "/trips/{$trip->id}/itinerary/{$item->id}/tips";
        $tips = $this->actingAs($owner)->getJson($url)->assertOk()->json('data');
        $this->assertSame([['title' => 'Swing from the rope into the pool', 'detail' => 'Go before 9 AM to beat the crowds.'], ['title' => 'Ride the bamboo raft', 'detail' => null]], $tips);
        Http::assertSent(fn ($request) => str_contains((string) $request->body(), 'Cambugahay Falls, Lazi, Siquijor'));
        $this->actingAs($owner)->getJson($url)->assertOk()->assertJsonCount(2, 'data');
        Http::assertSentCount(1);
    }

    public function test_day_prep_summarises_what_to_wear_and_bring_and_follows_plan_changes(): void
    {
        config(['services.gemini.api_key' => 'test-key', 'services.gemini.credentials' => null, 'services.gemini.project' => 'demo', 'services.gemini.model' => 'gemini-2.5-flash']);
        [$owner, $trip, $item] = $this->setUpTrip();
        $item->update(['starts_at' => \Carbon\CarbonImmutable::parse('2026-11-12 09:00', 'Asia/Manila')->utc(), 'ends_at' => \Carbon\CarbonImmutable::parse('2026-11-12 11:00', 'Asia/Manila')->utc()]);
        Http::fake(['aiplatform.googleapis.com/*' => Http::response(['candidates' => [['content' => ['parts' => [['text' => json_encode([
            'summary' => 'A waterfall morning.', 'outfit' => ['Swimsuit under quick-dry clothes', ''], 'bring' => ['Dry bag', 'Cash for entrance'], 'tips' => ['Go early'],
        ])]]]]]])]);
        $url = "/trips/{$trip->id}/itinerary-day?date=2026-11-12";
        $this->actingAs($owner)->getJson($url)->assertOk()->assertJson(['data' => ['date' => '2026-11-12', 'stops' => 1, 'summary' => 'A waterfall morning.', 'outfit' => ['Swimsuit under quick-dry clothes'], 'bring' => ['Dry bag', 'Cash for entrance'], 'tips' => ['Go early']]]);
        Http::assertSent(fn ($request) => str_contains((string) $request->body(), '9:00 AM') && str_contains((string) $request->body(), 'Cambugahay Falls'));
        $this->actingAs($owner)->getJson($url)->assertOk();
        Http::assertSentCount(1);                                   // cached
        $item->update(['title' => 'Cambugahay Falls and Lazi Church']);
        $this->actingAs($owner)->getJson($url)->assertOk();
        Http::assertSentCount(2);                                   // the plan changed: rewritten
        $this->actingAs($owner)->getJson("/trips/{$trip->id}/itinerary-day?date=2026-11-11")->assertOk()->assertJsonPath('data.stops', 0)->assertJsonPath('data.summary', null);
    }

    public function test_only_travellers_see_them(): void
    {
        config(['services.google_maps.browser_key' => '']);
        [, $trip, $item] = $this->setUpTrip();
        Http::fake(['commons.wikimedia.org/*' => Http::response(['query' => ['pages' => []]])]);
        $this->actingAs(User::factory()->create())->getJson("/trips/{$trip->id}/itinerary/{$item->id}/photos")->assertForbidden();
    }
}
