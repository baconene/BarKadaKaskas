<?php

namespace Tests\Feature;

use App\Models\{Expense, ExpenseCategory, Trip, TripInvitation, User};
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Http\UploadedFile;
use Illuminate\Support\Facades\Storage;
use Tests\TestCase;

class MobileApiTest extends TestCase
{
    use RefreshDatabase;

    private User $owner;
    private User $friend;
    private Trip $trip;
    private int $ownerMember;
    private int $friendMember;
    private int $category;

    protected function setUp(): void
    {
        parent::setUp();
        $this->withoutVite();
        $this->owner = User::factory()->create(); $this->friend = User::factory()->create();
        $this->trip = Trip::create(['organizer_user_id' => $this->owner->id, 'name' => 'Siquijor', 'destination' => 'Siquijor', 'starts_on' => '2026-11-11', 'ends_on' => '2026-11-17', 'timezone' => 'Asia/Manila']);
        $this->ownerMember = $this->trip->members()->create(['user_id' => $this->owner->id, 'status' => 'active'])->id;
        $this->friendMember = $this->trip->members()->create(['user_id' => $this->friend->id, 'status' => 'active'])->id;
        $this->category = ExpenseCategory::create(['name' => 'Food'])->id;
    }

    /** Bearer headers for a user. Forgets the cached guard user so each request authenticates its own token, as in production. */
    private function headers(User $user): array
    {
        $this->app['auth']->forgetGuards();
        return ['Authorization' => 'Bearer '.$user->createToken('Test device', ['mobile'], now()->addDay())->plainTextToken];
    }

    private function expense(): array
    {
        return ['title' => 'Dinner', 'merchant' => 'Cafe', 'category_id' => $this->category, 'incurred_at' => '2026-11-11', 'total_minor' => 10001, 'split_method' => 'equal', 'payments' => [['trip_member_id' => $this->ownerMember, 'amount_minor' => 10001]], 'allocations' => [['trip_member_id' => $this->ownerMember, 'weight' => 1], ['trip_member_id' => $this->friendMember, 'weight' => 1]], 'items' => []];
    }

    public function test_mobile_registration_login_expiry_and_current_device_logout(): void
    {
        $this->get('/api/v1/trips')->assertUnauthorized()->assertJsonStructure(['message']);
        $registered = $this->postJson('/api/v1/auth/register', ['name' => 'New traveler', 'email' => 'NEW@example.com', 'password' => 'password123', 'password_confirmation' => 'password123', 'device_name' => 'Android'])->assertCreated()->assertJsonPath('user.email', 'new@example.com');
        $this->assertArrayNotHasKey('password', $registered->json('user'));
        $firstToken = $registered->json('token');
        $this->postJson('/api/v1/auth/login', ['email' => 'new@example.com', 'password' => 'wrong'])->assertUnprocessable()->assertJsonValidationErrors('email');
        $login = $this->postJson('/api/v1/auth/login', ['email' => 'new@example.com', 'password' => 'password123', 'device_name' => 'iPhone'])->assertOk();
        $headers = ['Authorization' => 'Bearer '.$login->json('token')];
        $this->getJson('/api/v1/auth/me', $headers)->assertOk()->assertJsonPath('user.name', 'New traveler');
        $this->postJson('/api/v1/auth/logout', [], $headers)->assertNoContent();
        $this->app['auth']->forgetGuards();
        $this->getJson('/api/v1/auth/me', $headers)->assertUnauthorized();
        $this->app['auth']->forgetGuards();
        $this->getJson('/api/v1/auth/me', ['Authorization' => 'Bearer '.$firstToken])->assertOk();
        $this->app['auth']->forgetGuards();
        $mixed = User::factory()->create(['email' => 'Mixed.Case@Example.com', 'password' => 'password123']);
        $this->postJson('/api/v1/auth/login', ['email' => 'mixed.case@example.com', 'password' => 'password123'])->assertOk()->assertJsonPath('user.id', $mixed->id);
        $this->app['auth']->forgetGuards();
        $expired = $this->owner->createToken('Expired', ['mobile'], now()->subMinute())->plainTextToken;
        $this->getJson('/api/v1/auth/me', ['Authorization' => 'Bearer '.$expired])->assertUnauthorized();
    }

    public function test_trip_contract_is_private_and_has_no_browser_keys_or_member_emails(): void
    {
        config(['services.google_maps.browser_key' => 'not-for-native-clients']);
        $headers = $this->headers($this->owner);
        $this->getJson('/api/v1/trips', $headers)->assertOk()->assertJsonCount(1, 'data')->assertJsonPath('data.0.members_count', 2);
        $detail = $this->getJson('/api/v1/trips/'.$this->trip->id, $headers)->assertOk()->assertJsonStructure(['data' => ['trip' => ['members', 'itinerary_items', 'expenses', 'settlements'], 'categories', 'balances', 'expense_shares', 'current_member_id', 'is_organizer']])->assertJsonPath('data.is_organizer', true)->assertJsonPath('data.current_member_id', $this->ownerMember);
        $this->assertStringNotContainsString('not-for-native-clients', $detail->getContent());
        $this->assertStringNotContainsString($this->friend->email, $detail->getContent());
        $this->assertStringContainsString('"expense_shares":{}', $detail->getContent());
        $outsider = User::factory()->create();
        $this->getJson('/api/v1/trips/'.$this->trip->id, $this->headers($outsider))->assertForbidden();
        $this->getJson('/api/v1/trips', $this->headers($outsider))->assertJsonCount(0, 'data');
        $this->trip->members()->whereKey($this->friendMember)->update(['status' => 'left']);
        $this->getJson('/api/v1/trips/'.$this->trip->id, $this->headers($this->friend))->assertForbidden();
    }

    public function test_trip_creation_and_itinerary_use_json_and_preserve_trip_timezone(): void
    {
        $headers = $this->headers($this->owner);
        $this->postJson('/api/v1/trips', ['name' => 'Bohol', 'destination' => 'Bohol', 'starts_on' => '2026-12-01', 'ends_on' => '2026-12-05', 'timezone' => 'Asia/Manila', 'currency_code' => 'PHP'], $headers)->assertCreated()->assertJsonPath('data.name', 'Bohol');
        $path = '/api/v1/trips/'.$this->trip->id.'/itinerary';
        $payload = ['title' => 'Lunch', 'location' => 'San Juan', 'starts_at' => '2026-11-12T12:00', 'ends_at' => '2026-11-12T13:00', 'transport_mode' => 'tricycle'];
        $stop = $this->postJson($path, $payload, $headers)->assertCreated()->assertJsonPath('data.transport_mode', 'tricycle');
        $this->assertStringStartsWith('2026-11-12T04:00:00', $stop->json('data.starts_at'));
        $id = $stop->json('data.id');
        $this->patchJson($path.'/'.$id, array_replace($payload, ['transport_mode' => 'walking']), $headers)->assertOk()->assertJsonPath('data.transport_mode', 'walking');
        $this->patchJson($path.'/'.$id, $payload, $this->headers($this->friend))->assertForbidden();
    }

    public function test_mobile_expenses_reuse_splitting_versions_and_lifecycle(): void
    {
        $headers = $this->headers($this->owner);
        $path = '/api/v1/trips/'.$this->trip->id.'/expenses';
        $response = $this->postJson($path, $this->expense(), $headers)->assertCreated()->assertJsonPath('data.status', 'open')->assertJsonPath('data.version', 1);
        $id = $response->json('data.id');
        $this->assertSame([5001, 5000], array_column($response->json('data.allocations'), 'amount_minor'));
        $this->patchJson($path.'/'.$id, $this->expense() + ['version' => 1], $headers)->assertOk()->assertJsonPath('data.version', 2);
        $this->patchJson($path.'/'.$id.'/status', ['status' => 'completed', 'version' => 1], $headers)->assertUnprocessable();
        $this->patchJson($path.'/'.$id.'/status', ['status' => 'completed', 'version' => 2], $headers)->assertOk()->assertJsonPath('data.status', 'completed');
        $this->postJson($path.'/'.$id.'/shares/paid', ['member_id' => $this->friendMember, 'version' => 3, 'new_payment' => true], $headers)->assertOk()->assertJsonPath('data.version', 4);
        $this->getJson('/api/v1/trips/'.$this->trip->id, $headers)->assertJsonPath('data.expense_shares.'.$id.'.1.remaining_minor', 0);
        $this->patchJson($path.'/'.$id.'/status', ['status' => 'closed', 'version' => 4], $headers)->assertOk();
        $this->patchJson($path.'/'.$id, $this->expense() + ['version' => 5], $headers)->assertUnprocessable();
        $this->patchJson($path.'/'.$id.'/status', ['status' => 'cancelled', 'version' => 5, 'reason' => 'Changed plans'], $this->headers($this->friend))->assertForbidden();
    }

    public function test_payments_cancellations_and_cross_trip_expenses_are_guarded(): void
    {
        $headers = $this->headers($this->owner);
        $path = '/api/v1/trips/'.$this->trip->id.'/expenses';
        $expense = $this->postJson($path, array_replace($this->expense(), ['payments' => []]), $headers)->assertCreated()->json('data');
        $this->postJson($path.'/'.$expense['id'].'/payments', ['trip_member_id' => $this->ownerMember, 'amount_minor' => 10001, 'version' => 1], $headers)->assertOk()->assertJsonPath('data.version', 2);
        $this->patchJson($path.'/'.$expense['id'].'/status', ['status' => 'cancelled', 'version' => 2, 'reason' => 'Cancelled'], $headers)->assertUnprocessable();
        $this->patchJson($path.'/'.$expense['id'].'/status', ['status' => 'cancelled', 'version' => 2, 'reason' => 'Cancelled', 'void_payments' => true], $headers)->assertOk()->assertJsonPath('data.status', 'cancelled');
        $other = Trip::create(['organizer_user_id' => $this->owner->id, 'name' => 'Other', 'destination' => 'Other', 'starts_on' => '2026-11-11', 'ends_on' => '2026-11-11']);
        $this->patchJson('/api/v1/trips/'.$other->id.'/expenses/'.$expense['id'], $this->expense() + ['version' => 3], $headers)->assertNotFound();
    }

    public function test_settlement_sender_and_confirmation_cannot_be_spoofed(): void
    {
        $path = '/api/v1/trips/'.$this->trip->id.'/settlements';
        $response = $this->postJson($path, ['to_member_id' => $this->ownerMember, 'from_member_id' => $this->ownerMember, 'amount_minor' => 5000, 'payment_method' => 'gcash'], $this->headers($this->friend))->assertCreated()->assertJsonPath('data.from_member_id', $this->friendMember)->assertJsonPath('data.status', 'pending');
        $id = $response->json('data.id');
        $this->postJson($path.'/'.$id.'/confirm', [], $this->headers($this->friend))->assertForbidden();
        $this->postJson($path.'/'.$id.'/confirm', [], $this->headers($this->owner))->assertOk()->assertJsonPath('data.status', 'confirmed');
    }

    public function test_receipt_upload_and_download_remain_private(): void
    {
        Storage::fake('local');
        $headers = $this->headers($this->owner);
        $path = '/api/v1/trips/'.$this->trip->id.'/expenses';
        $id = $this->postJson($path, $this->expense(), $headers)->json('data.id');
        $upload = $this->post($path.'/'.$id.'/receipts', ['receipt' => UploadedFile::fake()->create('receipt.pdf', 2, 'application/pdf')], $headers)->assertCreated();
        $this->assertArrayNotHasKey('storage_path', $upload->json('data'));
        $receiptId = $upload->json('data.id');
        $this->get('/api/v1/receipts/'.$receiptId, $headers)->assertOk();
        $this->getJson('/api/v1/receipts/'.$receiptId, $this->headers(User::factory()->create()))->assertForbidden();
        $detail = $this->getJson('/api/v1/trips/'.$this->trip->id, $this->headers($this->owner))->assertOk();
        $this->assertStringNotContainsString('storage_path', $detail->getContent());
        $this->assertStringNotContainsString('file_hash', $detail->getContent());
    }

    public function test_public_invitation_preview_and_authenticated_join(): void
    {
        $invite = $this->postJson('/api/v1/trips/'.$this->trip->id.'/invitations', [], $this->headers($this->owner))->assertCreated();
        $token = $invite->json('data.token');
        $this->getJson('/api/v1/invitations/'.$token)->assertOk()->assertJsonPath('data.trip_name', 'Siquijor')->assertJsonMissingPath('data.members')->assertJsonMissingPath('data.expenses');
        $newUser = User::factory()->create();
        $this->postJson('/api/v1/invitations/'.$token.'/join', [], $this->headers($newUser))->assertOk()->assertJsonPath('data.trip_id', $this->trip->id);
        $this->postJson('/api/v1/invitations/'.$token.'/join', [], $this->headers($newUser))->assertOk();
        $this->assertSame(1, TripInvitation::firstOrFail()->uses_count);
        TripInvitation::query()->update(['revoked_at' => now()]);
        $this->getJson('/api/v1/invitations/'.$token)->assertGone();
    }

    public function test_android_patch_override_header_reaches_patch_routes(): void
    {
        // Android's HttpURLConnection cannot send PATCH, so the app POSTs with X-HTTP-Method-Override.
        $headers = $this->headers($this->owner);
        $path = '/api/v1/trips/'.$this->trip->id.'/itinerary';
        $payload = ['title' => 'Lunch', 'location' => 'San Juan', 'starts_at' => '2026-11-12T12:00', 'ends_at' => '2026-11-12T13:00'];
        $id = $this->postJson($path, $payload, $headers)->assertCreated()->json('data.id');
        $this->postJson($path.'/'.$id, ['title' => 'Late lunch'] + $payload, $headers + ['X-HTTP-Method-Override' => 'PATCH'])
            ->assertOk()->assertJsonPath('data.title', 'Late lunch');
    }
}
