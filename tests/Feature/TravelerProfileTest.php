<?php

namespace Tests\Feature;

use App\Models\{Trip, User};
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Http\UploadedFile;
use Illuminate\Support\Facades\Storage;
use Tests\TestCase;

class TravelerProfileTest extends TestCase
{
    use RefreshDatabase;

    private function headers(User $user): array
    {
        $this->app['auth']->forgetGuards();
        return ['Authorization' => 'Bearer '.$user->createToken('Test device', ['mobile'], now()->addDay())->plainTextToken];
    }

    public function test_travellers_save_contact_and_emergency_details_seen_only_by_trip_mates(): void
    {
        [$owner, $friend, $stranger] = [User::factory()->create(), User::factory()->create(), User::factory()->create()];
        $trip = Trip::create(['organizer_user_id' => $owner->id, 'name' => 'Siquijor', 'destination' => 'Siquijor', 'starts_on' => '2026-11-11', 'ends_on' => '2026-11-17']);
        $trip->members()->create(['user_id' => $owner->id, 'status' => 'active']);
        $trip->members()->create(['user_id' => $friend->id, 'status' => 'active']);

        $this->patchJson('/api/v1/me/profile', ['phone' => '+63 917 123 4567', 'other_contact' => 'Messenger: @jamie', 'emergency_contact_name' => 'Maria Cruz', 'emergency_contact_relationship' => 'Mother', 'emergency_contact_phone' => '0917 765 4321'], $this->headers($friend))
            ->assertOk()->assertJsonPath('data.phone', '+63 917 123 4567')->assertJsonPath('data.emergency_contact_name', 'Maria Cruz');

        $members = $this->getJson("/api/v1/trips/{$trip->id}", $this->headers($owner))->assertOk()->json('data.trip.members');
        $user = collect($members)->firstWhere('user_id', $friend->id)['user'];
        $this->assertSame('0917 765 4321', $user['emergency_contact_phone']);
        $this->assertArrayNotHasKey('email', $user);
        $this->assertArrayNotHasKey('avatar_path', $user);
        $this->getJson("/api/v1/trips/{$trip->id}", $this->headers($stranger))->assertForbidden();
    }

    public function test_validation_and_clearing_fields(): void
    {
        $user = User::factory()->create(['phone' => '0917']);
        $this->patchJson('/api/v1/me/profile', ['phone' => 'call me'], $this->headers($user))->assertUnprocessable()->assertJsonValidationErrors('phone');
        $this->patchJson('/api/v1/me/profile', ['emergency_contact_name' => 'Maria'], $this->headers($user))->assertUnprocessable()->assertJsonValidationErrors('emergency_contact_phone');
        $this->patchJson('/api/v1/me/profile', ['phone' => '  '], $this->headers($user))->assertOk()->assertJsonPath('data.phone', null);
    }

    public function test_profile_picture_upload_replace_and_remove(): void
    {
        Storage::fake('local');
        $user = User::factory()->create();
        $first = $this->postJson('/api/v1/me/avatar', ['avatar' => UploadedFile::fake()->image('me.jpg', 400, 400)], $this->headers($user))->assertOk()->json('data.avatar_url');
        $this->assertMatchesRegularExpression('#/avatars/[A-Za-z0-9]{40}\.jpg$#', $first);
        $old = $user->fresh()->avatar_path;
        $this->app['auth']->forgetGuards();
        $this->get(parse_url($first, PHP_URL_PATH))->assertOk();
        $this->postJson('/api/v1/me/avatar', ['avatar' => UploadedFile::fake()->image('me2.png', 400, 400)], $this->headers($user))->assertOk();
        Storage::disk('local')->assertMissing($old);
        $this->deleteJson('/api/v1/me/avatar', [], $this->headers($user))->assertOk()->assertJsonPath('data.avatar_url', null);
        $this->postJson('/api/v1/me/avatar', ['avatar' => UploadedFile::fake()->create('x.pdf', 10, 'application/pdf')], $this->headers($user))->assertUnprocessable();
    }

    public function test_web_profile_update_redirects_back(): void
    {
        $user = User::factory()->create();
        $this->actingAs($user)->from('/dashboard')->patch('/me/profile', ['phone' => '0917 000 0000'])->assertRedirect('/dashboard');
        $this->assertSame('0917 000 0000', $user->fresh()->phone);
    }
}
