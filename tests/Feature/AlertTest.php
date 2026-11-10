<?php

namespace Tests\Feature;

use App\Models\{Alert, ExpenseCategory, Trip, User};
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Http\UploadedFile;
use Illuminate\Support\Facades\Storage;
use Tests\TestCase;

class AlertTest extends TestCase
{
    use RefreshDatabase;

    private User $owner;
    private User $friend;
    private User $other;
    private Trip $trip;
    private array $members = [];

    protected function setUp(): void
    {
        parent::setUp();
        Storage::fake('local');
        [$this->owner, $this->friend, $this->other] = [User::factory()->create(['name' => 'Jamie Cruz']), User::factory()->create(['name' => 'Bea Santos']), User::factory()->create(['name' => 'Carlo Reyes'])];
        $this->trip = Trip::create(['organizer_user_id' => $this->owner->id, 'name' => 'Siquijor', 'destination' => 'Siquijor', 'starts_on' => '2026-11-11', 'ends_on' => '2026-11-17', 'timezone' => 'Asia/Manila', 'currency_code' => 'PHP']);
        foreach ([$this->owner, $this->friend, $this->other] as $user) { $this->members[$user->id] = $this->trip->members()->create(['user_id' => $user->id, 'status' => 'active'])->id; }
    }

    private function headers(User $user): array
    {
        $this->app['auth']->forgetGuards();
        return ['Authorization' => 'Bearer '.$user->createToken('Test device', ['mobile'], now()->addDay())->plainTextToken];
    }

    public function test_others_are_alerted_about_photos_comments_expenses_and_plan_changes_but_not_the_actor(): void
    {
        $post = $this->postJson("/api/v1/trips/{$this->trip->id}/posts", ['caption' => 'Falls!', 'photos' => [UploadedFile::fake()->image('a.jpg')]], $this->headers($this->friend))->assertCreated()->json('data');
        $this->assertSame(0, Alert::where('user_id', $this->friend->id)->count());
        $this->assertSame('Bea posted a photo', Alert::where('user_id', $this->owner->id)->value('title'));

        // A comment reaches the post's author (and later commenters), not everyone.
        $this->postJson("/api/v1/trips/{$this->trip->id}/posts/{$post['id']}/comments", ['body' => 'Ganda!'], $this->headers($this->other))->assertCreated();
        $this->assertSame('Carlo commented on Bea’s photo', Alert::where('user_id', $this->friend->id)->where('type', 'comment')->value('title'));
        $this->assertSame(0, Alert::where('user_id', $this->owner->id)->where('type', 'comment')->count());

        $category = ExpenseCategory::create(['name' => 'Food'])->id;
        $this->postJson("/api/v1/trips/{$this->trip->id}/expenses", ['title' => 'Dinner', 'category_id' => $category, 'incurred_at' => '2026-11-12', 'total_minor' => 30000, 'split_method' => 'equal',
            'payments' => [['trip_member_id' => $this->members[$this->owner->id], 'amount_minor' => 30000]], 'allocations' => array_map(fn ($id) => ['trip_member_id' => $id, 'weight' => 1], array_values($this->members)), 'items' => []], $this->headers($this->owner))->assertCreated();
        $this->assertSame('Jamie added ₱300.00 · your share ₱100.00', Alert::where('user_id', $this->friend->id)->where('type', 'expense')->value('body'));

        $this->postJson("/api/v1/trips/{$this->trip->id}/itinerary", ['title' => 'Lunch', 'location' => 'San Juan', 'starts_at' => '2026-11-12T12:00', 'ends_at' => '2026-11-12T13:00'], $this->headers($this->owner))->assertCreated();
        $this->assertStringContainsString('Jamie added “Lunch”', (string) Alert::where('user_id', $this->other->id)->where('type', 'itinerary')->value('body'));
        $this->assertSame(0, Alert::where('user_id', $this->owner->id)->whereIn('type', ['expense', 'itinerary'])->count());
    }

    public function test_alerts_list_newer_ones_and_mark_read(): void
    {
        $this->postJson("/api/v1/trips/{$this->trip->id}/posts", ['photos' => [UploadedFile::fake()->image('a.jpg')]], $this->headers($this->friend))->assertCreated();
        $this->postJson("/api/v1/trips/{$this->trip->id}/posts", ['photos' => [UploadedFile::fake()->image('b.jpg'), UploadedFile::fake()->image('c.jpg')]], $this->headers($this->other))->assertCreated();
        $page = $this->getJson('/api/v1/alerts', $this->headers($this->owner))->assertOk()->assertJsonPath('unread_count', 2);
        [$newest, $oldest] = $page->json('data');
        $this->assertSame('Carlo posted 2 photos', $newest['title']);
        $this->assertSame('Siquijor', $newest['trip_name']);
        $this->assertCount(1, $this->getJson("/api/v1/alerts?after={$oldest['id']}", $this->headers($this->owner))->json('data'));
        $this->postJson('/api/v1/alerts/read', ['ids' => [$oldest['id']]], $this->headers($this->owner))->assertJsonPath('unread_count', 1);
        $this->postJson('/api/v1/alerts/read', [], $this->headers($this->owner))->assertJsonPath('unread_count', 0);
        $this->getJson('/api/v1/alerts/unread-count', $this->headers($this->friend))->assertJsonPath('unread_count', 1); // only Carlo's post
    }
}
