<?php

namespace Tests\Feature;

use App\Models\{Trip, User};
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Http;
use Inertia\Testing\AssertableInertia as Assert;
use Tests\TestCase;

class ItineraryPlannerTest extends TestCase
{
    use RefreshDatabase;

    private User $owner;
    private User $friend;
    private Trip $trip;

    protected function setUp(): void
    {
        parent::setUp();
        config(['services.gemini.api_key' => 'test-key', 'services.gemini.credentials' => null, 'services.gemini.project' => 'demo', 'services.gemini.model' => 'gemini-2.5-flash']);
        [$this->owner, $this->friend] = [User::factory()->create(), User::factory()->create()];
        $this->trip = Trip::create(['organizer_user_id' => $this->owner->id, 'name' => 'Siquijor', 'destination' => 'Siquijor, Philippines', 'starts_on' => '2026-11-11', 'ends_on' => '2026-11-12', 'timezone' => 'Asia/Manila', 'currency_code' => 'PHP']);
        foreach ([$this->owner, $this->friend] as $user) { $this->trip->members()->create(['user_id' => $user->id, 'status' => 'active']); }
    }

    private function headers(User $user): array
    {
        $this->app['auth']->forgetGuards();
        return ['Authorization' => 'Bearer '.$user->createToken('Test device', ['mobile'], now()->addDay())->plainTextToken];
    }

    private function fakeGemini(array $activities): void
    {
        Http::fake(['aiplatform.googleapis.com/*' => Http::response(['candidates' => [['content' => ['parts' => [['text' => json_encode(['activities' => $activities])]]], 'finishReason' => 'STOP']]])]);
    }

    public function test_organizer_gets_cleaned_up_suggestions_without_saving_anything(): void
    {
        $this->fakeGemini([
            ['title' => 'Cambugahay Falls', 'location' => 'Lazi, Siquijor', 'description' => 'Go early.', 'starts_at' => '2026-11-12T09:00', 'ends_at' => '2026-11-12T11:30', 'transport_mode' => 'tricycle', 'cost_min' => 100, 'cost_max' => 250.5, 'cost_note' => 'Entrance and raft'],
            ['title' => 'Arrive at Siquijor Port', 'location' => 'Siquijor Port', 'starts_at' => '2026-11-11T15:00', 'ends_at' => '2026-11-11T14:00', 'transport_mode' => 'helicopter', 'cost_min' => null, 'cost_max' => null],
            ['title' => 'Outside the trip', 'starts_at' => '2026-11-20T09:00', 'ends_at' => '2026-11-20T10:00'],
            ['title' => '', 'starts_at' => '2026-11-11T09:00', 'ends_at' => '2026-11-11T10:00'],
            ['title' => 'Bad time', 'starts_at' => 'tomorrow-ish', 'ends_at' => 'later'],
        ]);
        $items = $this->postJson("/api/v1/trips/{$this->trip->id}/itinerary/suggestions", ['prompt' => 'Waterfalls and beaches, budget'], $this->headers($this->owner))->assertOk()->json('data');

        $this->assertSame(['Arrive at Siquijor Port', 'Cambugahay Falls'], array_column($items, 'title'));
        $this->assertSame('2026-11-11T16:00', $items[0]['ends_at']); // an end before the start becomes an hour later
        $this->assertNull($items[0]['transport_mode']);                // unknown modes are dropped
        $this->assertSame([10000, 25050], [$items[1]['cost_min_minor'], $items[1]['cost_max_minor']]);
        $this->assertDatabaseCount('itinerary_items', 0);
        Http::assertSent(fn ($request) => str_contains($request->body(), 'Waterfalls and beaches') && str_contains($request->body(), '2026-11-11 to 2026-11-12'));
    }

    public function test_approved_suggestions_are_added_in_one_go(): void
    {
        $items = [
            ['title' => 'Arrive at Siquijor Port', 'location' => 'Siquijor Port', 'starts_at' => '2026-11-11T15:00', 'ends_at' => '2026-11-11T15:30'],
            ['title' => 'Cambugahay Falls', 'location' => 'Lazi, Siquijor', 'starts_at' => '2026-11-12T09:00', 'ends_at' => '2026-11-12T11:30', 'transport_mode' => 'tricycle', 'cost_min_minor' => 10000, 'cost_max_minor' => 25000, 'cost_note' => 'Entrance'],
        ];
        $this->postJson("/api/v1/trips/{$this->trip->id}/itinerary/batch", ['items' => $items], $this->headers($this->owner))->assertCreated()->assertJsonCount(2, 'data');
        $falls = $this->trip->itineraryItems()->where('title', 'Cambugahay Falls')->firstOrFail();
        $this->assertSame('2026-11-12 01:00', $falls->starts_at->format('Y-m-d H:i')); // stored in UTC
        $this->assertSame([10000, 25000, 'tricycle'], [$falls->cost_min_minor, $falls->cost_max_minor, $falls->transport_mode]);
        $this->assertDatabaseHas('activity_logs', ['trip_id' => $this->trip->id, 'action' => 'itinerary_planned']);

        $this->postJson("/api/v1/trips/{$this->trip->id}/itinerary/batch", ['items' => []], $this->headers($this->owner))->assertJsonValidationErrors('items');
        $this->postJson("/api/v1/trips/{$this->trip->id}/itinerary/batch", ['items' => [array_replace($items[0], ['ends_at' => '2026-11-11T14:00'])]], $this->headers($this->owner))->assertJsonValidationErrors('items.0.ends_at');
        $this->assertDatabaseCount('itinerary_items', 2);
    }

    public function test_the_planner_can_move_planned_activities_and_they_are_applied_on_approval(): void
    {
        $item = fn ($title, $start, $done = false) => $this->trip->itineraryItems()->create(['title' => $title, 'starts_at' => \Carbon\CarbonImmutable::parse("2026-11-11 {$start}", 'Asia/Manila')->utc(), 'ends_at' => \Carbon\CarbonImmutable::parse("2026-11-11 {$start}", 'Asia/Manila')->addHours(2)->utc(), 'timezone' => 'Asia/Manila', 'created_by_user_id' => $this->owner->id, 'completed_at' => $done ? now() : null]);
        $beach = $item('Salagdoong Beach', '09:00'); $done = $item('Breakfast', '07:00', true);
        Http::fake(['aiplatform.googleapis.com/*' => Http::response(['candidates' => [['content' => ['parts' => [['text' => json_encode(['activities' => [], 'changes' => [
            ['item_id' => $beach->id, 'starts_at' => '2026-11-12T09:00', 'ends_at' => '2026-11-12T11:00', 'reason' => 'Busy on weekends; go on a weekday'],
            ['item_id' => $done->id, 'starts_at' => '2026-11-12T07:00', 'ends_at' => '2026-11-12T08:00'],   // done: left alone
            ['item_id' => 999999, 'starts_at' => '2026-11-12T07:00', 'ends_at' => '2026-11-12T08:00'],     // not on this trip
        ]])]]], 'finishReason' => 'STOP']]])]);
        $response = $this->postJson("/api/v1/trips/{$this->trip->id}/itinerary/suggestions", ['prompt' => 'Fix the itinerary: move the busy beach to tomorrow'], $this->headers($this->owner))->assertOk();
        $this->assertSame([], $response->json('data'));
        $this->assertSame([['item_id' => $beach->id, 'title' => 'Salagdoong Beach', 'from_starts_at' => '2026-11-11T09:00', 'from_ends_at' => '2026-11-11T11:00', 'starts_at' => '2026-11-12T09:00', 'ends_at' => '2026-11-12T11:00', 'reason' => 'Busy on weekends; go on a weekday']], $response->json('changes'));
        Http::assertSent(fn ($request) => str_contains($request->body(), '#'.$beach->id.' '));

        $this->postJson("/api/v1/trips/{$this->trip->id}/itinerary/batch", ['changes' => [['item_id' => $beach->id, 'starts_at' => '2026-11-12T09:00', 'ends_at' => '2026-11-12T11:00']]], $this->headers($this->owner))->assertCreated();
        $this->assertSame('2026-11-12 01:00', $beach->fresh()->starts_at->format('Y-m-d H:i'));
        $this->postJson("/api/v1/trips/{$this->trip->id}/itinerary/batch", ['changes' => [['item_id' => 999999, 'starts_at' => '2026-11-12T09:00', 'ends_at' => '2026-11-12T11:00']]], $this->headers($this->owner))->assertJsonValidationErrors('changes.0.item_id');
    }

    public function test_only_the_organizer_plans_and_errors_are_readable(): void
    {
        $this->fakeGemini([]);
        $this->postJson("/api/v1/trips/{$this->trip->id}/itinerary/suggestions", ['prompt' => 'Beaches'], $this->headers($this->friend))->assertForbidden();
        $this->postJson("/api/v1/trips/{$this->trip->id}/itinerary/batch", ['items' => [['title' => 'X', 'starts_at' => '2026-11-11T09:00', 'ends_at' => '2026-11-11T10:00']]], $this->headers($this->friend))->assertForbidden();
        $this->postJson("/api/v1/trips/{$this->trip->id}/itinerary/suggestions", ['prompt' => 'Beaches'], $this->headers($this->owner))->assertUnprocessable()->assertJsonPath('message', 'The AI planner could not suggest anything for that. Try describing what you want differently.');

        config(['services.gemini.api_key' => null]);
        $this->postJson("/api/v1/trips/{$this->trip->id}/itinerary/suggestions", ['prompt' => 'Beaches'], $this->headers($this->owner))->assertStatus(503);
        $this->getJson("/api/v1/trips/{$this->trip->id}", $this->headers($this->owner))->assertJsonPath('data.ai_planner', false);
    }

    public function test_creating_a_trip_with_an_ai_request_opens_the_planner(): void
    {
        $this->withoutVite();
        $response = $this->actingAs($this->owner)->post('/trips', ['name' => 'Bohol', 'destination' => 'Bohol', 'starts_on' => '2026-12-01', 'ends_on' => '2026-12-03', 'timezone' => 'Asia/Manila', 'currency_code' => 'PHP', 'ai_prompt' => 'Chocolate Hills and tarsiers']);
        $trip = Trip::where('name', 'Bohol')->firstOrFail();
        $response->assertRedirect("/trips/{$trip->id}")->assertSessionHas('ai_prompt', 'Chocolate Hills and tarsiers');
        $this->get("/trips/{$trip->id}")->assertInertia(fn (Assert $page) => $page->where('aiPrompt', 'Chocolate Hills and tarsiers')->where('aiPlanner', true));
    }
}
