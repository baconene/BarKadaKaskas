<?php

namespace Tests\Feature;

use App\Models\{ActivityLog, ExpenseCategory, Trip, User};
use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\TestCase;

class TripCancellationTest extends TestCase
{
    use RefreshDatabase;

    private User $owner;
    private User $friend;
    private Trip $trip;

    protected function setUp(): void
    {
        parent::setUp();
        $this->withoutVite();
        $this->owner = User::factory()->create(); $this->friend = User::factory()->create();
        $this->trip = Trip::create(['organizer_user_id' => $this->owner->id, 'name' => 'Siquijor', 'destination' => 'Siquijor', 'starts_on' => '2026-11-11', 'ends_on' => '2026-11-17', 'timezone' => 'Asia/Manila']);
        $this->trip->members()->create(['user_id' => $this->owner->id, 'status' => 'active']);
        $this->trip->members()->create(['user_id' => $this->friend->id, 'status' => 'active']);
    }

    private function headers(User $user): array
    {
        $this->app['auth']->forgetGuards();
        return ['Authorization' => 'Bearer '.$user->createToken('Test device', ['mobile'], now()->addDay())->plainTextToken];
    }

    public function test_only_the_organizer_can_cancel(): void
    {
        $this->postJson("/api/v1/trips/{$this->trip->id}/cancel", [], $this->headers($this->friend))->assertForbidden();
        $this->postJson("/api/v1/trips/{$this->trip->id}/cancel", ['reason' => 'Typhoon'], $this->headers($this->owner))
            ->assertOk()->assertJsonPath('data.status', 'cancelled')->assertJsonPath('data.cancellation_reason', 'Typhoon')->assertJsonPath('data.hidden_at', null);
        $this->postJson("/api/v1/trips/{$this->trip->id}/cancel", [], $this->headers($this->owner))->assertUnprocessable();
        $this->assertTrue(ActivityLog::where('subject_type', 'trip')->where('action', 'cancelled')->exists());
    }

    public function test_cancelled_trip_stays_listed_unless_hidden(): void
    {
        $this->postJson("/api/v1/trips/{$this->trip->id}/cancel", [], $this->headers($this->owner))->assertOk();
        $this->getJson('/api/v1/trips', $this->headers($this->friend))->assertJsonCount(1, 'data')->assertJsonPath('data.0.status', 'cancelled')->assertJsonPath('meta.hidden_count', 0);
    }

    public function test_hidden_trip_disappears_from_everyones_list_but_can_be_found_and_restored(): void
    {
        $this->postJson("/api/v1/trips/{$this->trip->id}/cancel", ['hide' => true], $this->headers($this->owner))->assertOk();
        foreach ([$this->owner, $this->friend] as $user) {
            $this->getJson('/api/v1/trips', $this->headers($user))->assertJsonCount(0, 'data')->assertJsonPath('meta.hidden_count', 1);
            $this->getJson('/api/v1/trips?hidden=1', $this->headers($user))->assertJsonCount(1, 'data');
        }
        $this->patchJson("/api/v1/trips/{$this->trip->id}/visibility", ['hidden' => false], $this->headers($this->friend))->assertForbidden();
        $this->patchJson("/api/v1/trips/{$this->trip->id}/visibility", ['hidden' => false], $this->headers($this->owner))->assertOk();
        $this->getJson('/api/v1/trips', $this->headers($this->friend))->assertJsonCount(1, 'data');
    }

    public function test_only_cancelled_trips_can_be_hidden_and_reopening_restores_everything(): void
    {
        $this->patchJson("/api/v1/trips/{$this->trip->id}/visibility", ['hidden' => true], $this->headers($this->owner))->assertUnprocessable();
        $this->postJson("/api/v1/trips/{$this->trip->id}/cancel", ['hide' => true, 'reason' => 'Rain'], $this->headers($this->owner))->assertOk();
        $this->postJson("/api/v1/trips/{$this->trip->id}/reopen", [], $this->headers($this->friend))->assertForbidden();
        $this->postJson("/api/v1/trips/{$this->trip->id}/reopen", [], $this->headers($this->owner))
            ->assertOk()->assertJsonPath('data.status', 'planning')->assertJsonPath('data.hidden_at', null)->assertJsonPath('data.cancellation_reason', null);
    }

    public function test_cancelled_trip_locks_plans_and_new_expenses(): void
    {
        $category = ExpenseCategory::create(['name' => 'Food'])->id;
        $this->postJson("/api/v1/trips/{$this->trip->id}/cancel", [], $this->headers($this->owner))->assertOk();
        $headers = $this->headers($this->owner);
        $this->postJson("/api/v1/trips/{$this->trip->id}/itinerary", ['title' => 'Falls', 'starts_at' => '2026-11-11T09:00', 'ends_at' => '2026-11-11T10:00'], $headers)
            ->assertForbidden()->assertJsonPath('message', 'This trip was cancelled. Reopen it to change the plan.');
        $this->postJson("/api/v1/trips/{$this->trip->id}/invitations", [], $headers)->assertForbidden();
        $this->postJson("/api/v1/trips/{$this->trip->id}/expenses", ['title' => 'Dinner', 'category_id' => $category, 'incurred_at' => '2026-11-11', 'total_minor' => 1000, 'split_method' => 'equal', 'payments' => [], 'allocations' => [], 'items' => []], $headers)->assertForbidden();
        // Members can still open the trip to settle up.
        $this->getJson("/api/v1/trips/{$this->trip->id}", $this->headers($this->friend))->assertOk()->assertJsonPath('data.trip.status', 'cancelled');
    }

    public function test_web_cancel_and_hide_returns_to_trip_list(): void
    {
        $this->actingAs($this->owner)->post(route('trips.cancel', $this->trip), ['hide' => true])->assertRedirect(route('dashboard'));
        $this->actingAs($this->owner)->get(route('dashboard'))->assertOk()
            ->assertInertia(fn ($page) => $page->component('Trips/Index')->has('trips', 0)->where('hiddenCount', 1)->where('showingHidden', false));
    }
}
