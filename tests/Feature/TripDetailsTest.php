<?php

namespace Tests\Feature;

use App\Models\{ExpenseCategory, Trip, User};
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Http\UploadedFile;
use Illuminate\Support\Facades\Storage;
use Tests\TestCase;

class TripDetailsTest extends TestCase
{
    use RefreshDatabase;

    private User $owner;
    private User $friend;
    private Trip $trip;

    protected function setUp(): void
    {
        parent::setUp();
        $this->withoutVite();
        Storage::fake('local');
        $this->owner = User::factory()->create(); $this->friend = User::factory()->create();
        $this->trip = Trip::create(['organizer_user_id' => $this->owner->id, 'name' => 'LANY Concert', 'destination' => 'Philippine Arena, Bulacan', 'starts_on' => '2026-11-08', 'ends_on' => '2026-11-08', 'timezone' => 'Asia/Manila', 'currency_code' => 'PHP']);
        $this->trip->members()->create(['user_id' => $this->owner->id, 'status' => 'active']);
        $this->trip->members()->create(['user_id' => $this->friend->id, 'status' => 'active']);
    }

    private function headers(User $user): array
    {
        $this->app['auth']->forgetGuards();
        return ['Authorization' => 'Bearer '.$user->createToken('Test device', ['mobile'], now()->addDay())->plainTextToken];
    }

    private function details(array $overrides = []): array
    {
        return array_merge(['name' => 'LANY: a beautiful blur', 'destination' => 'Philippine Arena, Bocaue, Bulacan', 'description' => 'Carpool from QC', 'starts_on' => '2026-11-08', 'ends_on' => '2026-11-09', 'timezone' => 'Asia/Manila', 'currency_code' => 'PHP'], $overrides);
    }

    public function test_organizer_edits_details_and_members_cannot(): void
    {
        $this->patchJson("/api/v1/trips/{$this->trip->id}", $this->details(), $this->headers($this->friend))->assertForbidden();
        $this->patchJson("/api/v1/trips/{$this->trip->id}", $this->details(), $this->headers($this->owner))
            ->assertOk()->assertJsonPath('data.name', 'LANY: a beautiful blur')->assertJsonPath('data.ends_on', '2026-11-09')->assertJsonPath('data.description', 'Carpool from QC');
        $this->patchJson("/api/v1/trips/{$this->trip->id}", $this->details(['ends_on' => '2026-11-01']), $this->headers($this->owner))->assertUnprocessable()->assertJsonValidationErrors('ends_on');
    }

    public function test_currency_is_locked_once_expenses_exist(): void
    {
        $this->patchJson("/api/v1/trips/{$this->trip->id}", $this->details(['currency_code' => 'USD']), $this->headers($this->owner))->assertOk();
        $this->trip->expenses()->create(['created_by_member_id' => $this->trip->members()->where('user_id', $this->owner->id)->value('id'), 'title' => 'Tickets', 'category_id' => ExpenseCategory::create(['name' => 'Fun'])->id, 'incurred_at' => '2026-11-08', 'total_minor' => 1000, 'split_method' => 'equal', 'currency_code' => 'USD', 'status' => 'open']);
        $this->patchJson("/api/v1/trips/{$this->trip->id}", $this->details(['currency_code' => 'PHP']), $this->headers($this->owner))->assertUnprocessable()->assertJsonValidationErrors('currency_code');
    }

    public function test_organizer_uploads_replaces_and_removes_cover_photo(): void
    {
        $this->postJson("/api/v1/trips/{$this->trip->id}/cover", ['cover' => UploadedFile::fake()->image('cover.jpg', 1200, 800)], $this->headers($this->friend))->assertForbidden();
        $first = $this->postJson("/api/v1/trips/{$this->trip->id}/cover", ['cover' => UploadedFile::fake()->image('cover.jpg', 1200, 800)], $this->headers($this->owner))->assertOk()->json('data');
        $this->assertArrayNotHasKey('cover_path', $first);
        $this->assertMatchesRegularExpression('#/trip-covers/[A-Za-z0-9]{40}\.jpg$#', $first['cover_url']);
        $oldPath = $this->trip->fresh()->cover_path;
        Storage::disk('local')->assertExists($oldPath);

        // Served without a session, so the mobile apps can load it as a plain image URL.
        $this->app['auth']->forgetGuards();
        $served = $this->get(parse_url($first['cover_url'], PHP_URL_PATH))->assertOk();
        $this->assertStringContainsString('max-age=31536000', (string) $served->headers->get('Cache-Control'));

        $second = $this->postJson("/api/v1/trips/{$this->trip->id}/cover", ['cover' => UploadedFile::fake()->image('new.png', 800, 600)], $this->headers($this->owner))->assertOk()->json('data');
        $this->assertNotSame($first['cover_url'], $second['cover_url']);
        Storage::disk('local')->assertMissing($oldPath);
        $this->getJson('/api/v1/trips', $this->headers($this->friend))->assertJsonPath('data.0.cover_url', $second['cover_url']);

        $this->deleteJson("/api/v1/trips/{$this->trip->id}/cover", [], $this->headers($this->owner))->assertOk()->assertJsonPath('data.cover_url', null);
    }

    public function test_cover_rejects_non_images_and_bad_names(): void
    {
        $this->postJson("/api/v1/trips/{$this->trip->id}/cover", ['cover' => UploadedFile::fake()->create('doc.pdf', 100, 'application/pdf')], $this->headers($this->owner))->assertUnprocessable();
        $this->get('/trip-covers/..%2F..%2F.env')->assertNotFound();
        $this->get('/trip-covers/'.str_repeat('a', 40).'.jpg')->assertNotFound();
    }

    public function test_cancelled_trip_details_are_locked(): void
    {
        $this->trip->update(['status' => 'cancelled']);
        $this->patchJson("/api/v1/trips/{$this->trip->id}", $this->details(), $this->headers($this->owner))->assertForbidden();
    }

    public function test_web_edit_redirects_back(): void
    {
        $this->actingAs($this->owner)->from(route('trips.show', $this->trip))->patch(route('trips.update', $this->trip), $this->details())->assertRedirect(route('trips.show', $this->trip));
        $this->assertSame('LANY: a beautiful blur', $this->trip->fresh()->name);
    }
}
