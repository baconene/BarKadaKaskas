<?php

namespace Tests\Feature;

use App\Models\{PremiumPayment, Trip, User};
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Http;
use Tests\TestCase;

class PremiumTest extends TestCase
{
    use RefreshDatabase;

    private User $owner;
    private User $friend;
    private Trip $trip;

    protected function setUp(): void
    {
        parent::setUp();
        config(['services.gemini.api_key' => 'test-key', 'services.gemini.credentials' => null, 'services.gemini.project' => 'demo',
            'services.paymongo.secret_key' => 'sk_test_x', 'services.paymongo.webhook_secret' => 'whsk_test', 'services.premium.price_minor' => 145000, 'services.premium.owner_emails' => '']);
        [$this->owner, $this->friend] = [User::factory()->create(), User::factory()->create()];
        $this->trip = Trip::create(['organizer_user_id' => $this->owner->id, 'name' => 'Vigan', 'destination' => 'Vigan', 'starts_on' => '2026-12-04', 'ends_on' => '2026-12-07', 'timezone' => 'Asia/Manila', 'currency_code' => 'PHP']);
        foreach ([$this->owner, $this->friend] as $user) { $this->trip->members()->create(['user_id' => $user->id, 'status' => 'active']); }
    }

    private function headers(User $user): array
    {
        $this->app['auth']->forgetGuards();
        return ['Authorization' => 'Bearer '.$user->createToken('Test device', ['mobile'], now()->addDay())->plainTextToken];
    }

    public function test_ai_is_only_for_the_organizer_with_premium(): void
    {
        $suggest = fn (User $user) => $this->postJson("/api/v1/trips/{$this->trip->id}/itinerary/suggestions", ['prompt' => 'Heritage walk'], $this->headers($user));
        $suggest($this->owner)->assertStatus(402)->assertJsonPath('message', 'AI features need Premium (₱1,450.00 a month). Go Premium in Settings → Premium.');
        $detail = $this->getJson("/api/v1/trips/{$this->trip->id}", $this->headers($this->owner))->json('data');
        $this->assertSame([false, true, false], [$detail['ai_planner'], $detail['ai_upsell'], $detail['premium']['active']]);
        $this->assertSame(false, $this->getJson("/api/v1/trips/{$this->trip->id}/itinerary-day?date=2026-12-05", $this->headers($this->owner))->json('data.available'));

        $this->owner->forceFill(['premium_until' => now()->addDays(10)])->save();
        $this->assertTrue($this->getJson("/api/v1/trips/{$this->trip->id}", $this->headers($this->owner))->json('data.ai_planner'));
        // A joiner, even with Premium, can't use AI on someone else's trip.
        $this->friend->forceFill(['premium_until' => now()->addDays(10)])->save();
        $this->assertFalse($this->getJson("/api/v1/trips/{$this->trip->id}", $this->headers($this->friend))->json('data.ai_planner'));
        $this->postJson("/api/v1/trips/{$this->trip->id}/receipt-scans", ['receipt' => \Illuminate\Http\UploadedFile::fake()->image('r.jpg')], $this->headers($this->friend))->assertStatus(402);
        // Expired Premium is off again; the app owner's email always counts.
        $this->owner->forceFill(['premium_until' => now()->subDay()])->save();
        $this->assertFalse($this->getJson("/api/v1/trips/{$this->trip->id}", $this->headers($this->owner))->json('data.ai_planner'));
        config(['services.premium.owner_emails' => ' Someone@else.test, '.strtoupper($this->owner->email)]);
        $this->assertTrue($this->getJson("/api/v1/trips/{$this->trip->id}", $this->headers($this->owner))->json('data.ai_planner'));
    }

    public function test_checkout_then_payment_adds_30_days_once(): void
    {
        Http::fake([
            'api.paymongo.com/v1/checkout_sessions' => Http::response(['data' => ['id' => 'cs_123', 'attributes' => ['checkout_url' => 'https://checkout.paymongo.com/cs_123']]]),
            'api.paymongo.com/v1/checkout_sessions/cs_123' => Http::response(['data' => ['id' => 'cs_123', 'attributes' => ['payments' => [['attributes' => ['status' => 'paid']]]]]]),
        ]);
        $this->postJson('/api/v1/premium/checkout', [], $this->headers($this->owner))->assertOk()->assertJsonPath('data.checkout_url', 'https://checkout.paymongo.com/cs_123');
        Http::assertSent(fn ($r) => $r->url() === 'https://api.paymongo.com/v1/checkout_sessions' && $r['data']['attributes']['line_items'][0]['amount'] === 145000 && in_array('gcash', $r['data']['attributes']['payment_method_types'], true));
        $payment = PremiumPayment::firstOrFail();
        $this->assertSame(['cs_123', 'pending'], [$payment->checkout_session_id, $payment->status]);

        // Coming back from checkout confirms it with PayMongo.
        $this->actingAs($this->owner)->get("/premium/return/{$payment->id}")->assertRedirect('/settings/premium');
        $until = $this->owner->fresh()->premium_until;
        $this->assertEqualsWithDelta(now()->addDays(30)->timestamp, $until->timestamp, 5);
        // The webhook for the same payment doesn't add more days.
        $body = json_encode(['data' => ['attributes' => ['type' => 'checkout_session.payment.paid', 'data' => ['id' => 'cs_123', 'attributes' => []]]]]);
        $t = time(); $sig = hash_hmac('sha256', $t.'.'.$body, 'whsk_test');
        $this->call('POST', '/api/v1/webhooks/paymongo', [], [], [], ['CONTENT_TYPE' => 'application/json', 'HTTP_PAYMONGO_SIGNATURE' => "t={$t},te={$sig},li="], $body)->assertOk();
        $this->assertSame($until->timestamp, $this->owner->fresh()->premium_until->timestamp);
    }

    public function test_webhook_needs_a_valid_signature_and_extends_premium(): void
    {
        $this->owner->forceFill(['premium_until' => now()->addDays(5)])->save();
        $payment = PremiumPayment::create(['user_id' => $this->owner->id, 'checkout_session_id' => 'cs_9', 'amount_minor' => 145000, 'currency_code' => 'PHP']);
        $body = json_encode(['data' => ['attributes' => ['type' => 'checkout_session.payment.paid', 'data' => ['id' => 'cs_9', 'attributes' => []]]]]);
        $this->call('POST', '/api/v1/webhooks/paymongo', [], [], [], ['CONTENT_TYPE' => 'application/json', 'HTTP_PAYMONGO_SIGNATURE' => 't=1,te=forged,li='], $body)->assertStatus(401);
        $t = time(); $sig = hash_hmac('sha256', $t.'.'.$body, 'whsk_test');
        $this->call('POST', '/api/v1/webhooks/paymongo', [], [], [], ['CONTENT_TYPE' => 'application/json', 'HTTP_PAYMONGO_SIGNATURE' => "t={$t},te=,li={$sig}"], $body)->assertOk();
        $this->assertSame('paid', $payment->fresh()->status);
        // Paying while Premium adds 30 days after the current end.
        $this->assertEqualsWithDelta(now()->addDays(35)->timestamp, $this->owner->fresh()->premium_until->timestamp, 5);
        $this->getJson('/api/v1/premium', $this->headers($this->owner))->assertOk()->assertJsonPath('data.active', true)->assertJsonCount(1, 'data.payments');
    }
}
