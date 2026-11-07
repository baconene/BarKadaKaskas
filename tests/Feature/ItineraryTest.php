<?php

namespace Tests\Feature;

use App\Models\{Trip, User};
use Illuminate\Foundation\Testing\RefreshDatabase;
use Inertia\Testing\AssertableInertia as Assert;
use Tests\TestCase;

class ItineraryTest extends TestCase
{
    use RefreshDatabase;

    private function trip(User $owner): Trip
    {
        $trip = Trip::create(['organizer_user_id' => $owner->id, 'name' => 'Siquijor', 'destination' => 'Siquijor', 'starts_on' => '2026-11-11', 'ends_on' => '2026-11-17', 'timezone' => 'Asia/Manila']);
        $trip->members()->create(['user_id' => $owner->id, 'status' => 'active']);
        return $trip;
    }

    private function stop(): array
    {
        return ['title' => 'Lunch', 'location' => 'San Juan, Siquijor, Philippines', 'description' => 'Meet at noon', 'transport_mode' => 'tricycle', 'starts_at' => '2026-11-12T12:00', 'ends_at' => '2026-11-12T13:00'];
    }

    public function test_organizer_can_save_and_edit_transport_and_local_times(): void
    {
        $this->withoutVite();
        $owner = User::factory()->create(); $trip = $this->trip($owner);
        $this->actingAs($owner)->post("/trips/{$trip->id}/itinerary", $this->stop())->assertSessionHasNoErrors();
        $item = $trip->itineraryItems()->firstOrFail();
        $this->assertSame('tricycle', $item->transport_mode);
        $this->assertSame('2026-11-12 04:00', $item->starts_at->format('Y-m-d H:i'));
        $this->patch("/trips/{$trip->id}/itinerary/{$item->id}", array_replace($this->stop(), ['transport_mode' => 'walking', 'location' => 'Siquijor Port, Philippines']))->assertSessionHasNoErrors();
        $this->assertSame('walking', $item->fresh()->transport_mode);
        $this->get("/trips/{$trip->id}")->assertInertia(fn (Assert $page) => $page->component('Trips/Show')->where('trip.itinerary_items.0.transport_mode', 'walking')->where('trip.itinerary_items.0.description', 'Meet at noon'));
    }

    public function test_transport_validation_and_itinerary_permissions(): void
    {
        $owner = User::factory()->create(); $trip = $this->trip($owner);
        $this->actingAs($owner)->post("/trips/{$trip->id}/itinerary", array_replace($this->stop(), ['transport_mode' => 'invalid']))->assertSessionHasErrors('transport_mode');
        $this->post("/trips/{$trip->id}/itinerary", $this->stop())->assertSessionHasNoErrors();
        $item = $trip->itineraryItems()->firstOrFail();
        $otherTrip = $this->trip($owner);
        $this->patch("/trips/{$otherTrip->id}/itinerary/{$item->id}", $this->stop())->assertNotFound();
        $member = User::factory()->create();
        $trip->members()->create(['user_id' => $member->id, 'status' => 'active']);
        $this->actingAs($member)->patch("/trips/{$trip->id}/itinerary/{$item->id}", $this->stop())->assertForbidden();
        $this->post("/trips/{$trip->id}/itinerary", $this->stop())->assertForbidden();
        $this->assertDatabaseCount('itinerary_items', 1);
    }

    public function test_picked_place_coordinates_are_saved_and_cleared_with_the_pin(): void
    {
        $this->withoutVite();
        $owner = User::factory()->create(); $trip = $this->trip($owner);
        $pinned = array_replace($this->stop(), ['location' => 'Dumaguete Port, Dumaguete City, Negros Oriental, Philippines', 'transport_mode' => 'ferry', 'latitude' => 9.3058731, 'longitude' => 123.3083412]);
        $this->actingAs($owner)->post("/trips/{$trip->id}/itinerary", $pinned)->assertSessionHasNoErrors();
        $item = $trip->itineraryItems()->firstOrFail();
        $this->assertEqualsWithDelta(9.3058731, $item->latitude, 0.0000001);
        $this->assertEqualsWithDelta(123.3083412, $item->longitude, 0.0000001);
        $this->get("/trips/{$trip->id}")->assertInertia(fn (Assert $page) => $page->where('trip.itinerary_items.0.latitude', 9.3058731)->where('trip.itinerary_items.0.longitude', 123.3083412));

        // Editing the address by hand (no pin sent) drops the old coordinates.
        $this->patch("/trips/{$trip->id}/itinerary/{$item->id}", array_replace($this->stop(), ['location' => 'Siquijor Port, Philippines']))->assertSessionHasNoErrors();
        $this->assertNull($item->fresh()->latitude);

        // Coordinates without a location are meaningless and are not kept.
        $this->patch("/trips/{$trip->id}/itinerary/{$item->id}", array_replace($pinned, ['location' => '']))->assertSessionHasNoErrors();
        $this->assertNull($item->fresh()->longitude);
    }

    public function test_coordinates_must_be_valid_and_come_in_pairs(): void
    {
        $owner = User::factory()->create(); $trip = $this->trip($owner);
        $this->actingAs($owner)->post("/trips/{$trip->id}/itinerary", array_replace($this->stop(), ['latitude' => 95, 'longitude' => 123]))->assertSessionHasErrors('latitude');
        $this->post("/trips/{$trip->id}/itinerary", array_replace($this->stop(), ['latitude' => 9.3, 'longitude' => 200]))->assertSessionHasErrors('longitude');
        $this->post("/trips/{$trip->id}/itinerary", array_replace($this->stop(), ['latitude' => 9.3]))->assertSessionHasErrors('longitude');
        $this->assertDatabaseCount('itinerary_items', 0);
    }
}
