<?php

namespace Tests\Feature;

use App\Models\{PremiumPayment, Trip, User};
use App\Services\Billing\SubscriptionRenewals;
use App\Support\Usage;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Http;
use Inertia\Testing\AssertableInertia as Assert;
use Tests\TestCase;

/** Free trial with a saved card, automatic renewals, cancelling, monthly allowances and the pages that explain them. */
class SubscriptionTest extends TestCase
{
    use RefreshDatabase;

    private User $user;

    protected function setUp(): void
    {
        parent::setUp();
        config(['services.paymongo.secret_key' => 'sk_test_x', 'services.paymongo.public_key' => 'pk_test_x', 'services.premium.price_minor' => 145000,
            'services.premium.trial_days' => 1, 'services.premium.owner_emails' => '']);
        $this->user = User::factory()->create(['name' => 'Ana Cruz']);
    }

    private function fakeCardCheck(string $status = 'awaiting_capture'): void
    {
        Http::fake([
            'api.paymongo.com/v1/customers' => Http::response(['data' => ['id' => 'cus_1']]),
            'api.paymongo.com/v1/payment_intents' => Http::response(['data' => ['id' => 'pi_1', 'attributes' => ['client_key' => 'pi_1_client']]]),
            'api.paymongo.com/v1/payment_intents/pi_1/attach' => Http::response(['data' => ['id' => 'pi_1', 'attributes' => ['status' => $status]
                + ($status === 'awaiting_next_action' ? ['next_action' => ['redirect' => ['url' => 'https://bank.test/3ds']]] : [])]]),
            'api.paymongo.com/v1/payment_intents/pi_1/cancel' => Http::response(['data' => ['id' => 'pi_1', 'attributes' => ['status' => 'cancelled']]]),
            'api.paymongo.com/v1/payment_intents/pi_1' => Http::response(['data' => ['id' => 'pi_1', 'attributes' => ['status' => $status, 'payment_method' => 'pm_card1',
                'last_payment_error' => $status === 'awaiting_payment_method' ? ['failed_message' => 'Card declined'] : null]]]),
            'api.paymongo.com/v1/payment_methods/pm_card1' => Http::response(['data' => ['id' => 'pm_card1', 'attributes' => ['details' => ['brand' => 'visa', 'last4' => '4345']]]]),
        ]);
    }

    /** A user one hour into the free trial, with a saved card (as startTrial() leaves them). */
    private function trialUser(): User
    {
        $this->user->forceFill(['billing_status' => 'trialing', 'auto_renew' => true, 'trial_started_at' => now(), 'premium_until' => now()->addDay(),
            'paymongo_customer_id' => 'cus_1', 'paymongo_payment_method_id' => 'pm_card1', 'card_brand' => 'visa', 'card_last4' => '4345'])->save();
        return $this->user;
    }

    private function startTrial(): void
    {
        $id = $this->actingAs($this->user)->postJson('/premium/subscribe', ['accept_terms' => '1'])->assertOk()
            ->assertJsonPath('data.trial', true)->assertJsonPath('data.public_key', 'pk_test_x')->json('data.payment_id');
        $this->actingAs($this->user)->postJson("/premium/subscribe/{$id}/card", ['payment_method_id' => 'pm_card1'])->assertOk()->assertJsonPath('data.trial', true);
    }

    public function test_the_terms_must_be_accepted(): void
    {
        $this->actingAs($this->user)->postJson('/premium/subscribe', [])->assertStatus(422)->assertJsonValidationErrors('accept_terms');
    }

    public function test_saving_a_card_starts_the_one_day_trial_once_and_releases_the_hold(): void
    {
        $this->fakeCardCheck();
        $this->startTrial();
        $user = $this->user->fresh();
        $this->assertSame(['trialing', true, 'pm_card1', 'visa', '4345'], [$user->billing_status, $user->auto_renew, $user->paymongo_payment_method_id, $user->card_brand, $user->card_last4]);
        $this->assertEqualsWithDelta(now()->addDay()->timestamp, $user->premium_until->timestamp, 5);
        $this->assertNotNull($user->terms_accepted_at);
        $this->assertSame('trial', Usage::plan($user));
        // The card check is a manual-capture hold that is cancelled, and the card is saved for off-session charges.
        Http::assertSent(fn ($r) => $r->url() === 'https://api.paymongo.com/v1/payment_intents' && $r['data']['attributes']['capture_type'] === 'manual'
            && $r['data']['attributes']['amount'] === 29000 && $r['data']['attributes']['setup_future_usage']['session_type'] === 'off_session');
        Http::assertSent(fn ($r) => $r->url() === 'https://api.paymongo.com/v1/payment_intents/pi_1/cancel');
        // No second trial, and no card numbers ever reach the server (only a pm_ id).
        $this->assertFalse(\App\Support\Premium::trialAvailable($user));
        $this->actingAs($user)->postJson('/premium/subscribe', ['accept_terms' => '1'])->assertStatus(422);
        $this->actingAs($user)->postJson('/premium/subscribe/1/card', ['payment_method_id' => '4343434343434345'])->assertStatus(422);
    }

    public function test_a_declined_card_starts_nothing(): void
    {
        $this->fakeCardCheck('awaiting_payment_method');
        $id = $this->actingAs($this->user)->postJson('/premium/subscribe', ['accept_terms' => '1'])->json('data.payment_id');
        $this->actingAs($this->user)->postJson("/premium/subscribe/{$id}/card", ['payment_method_id' => 'pm_card1'])->assertStatus(422)->assertJsonPath('data.ok', false);
        $this->assertNull($this->user->fresh()->premium_until);
        $this->assertTrue(\App\Support\Premium::trialAvailable($this->user->fresh()));
    }

    public function test_3d_secure_sends_the_payer_to_the_bank_and_back(): void
    {
        $this->fakeCardCheck('awaiting_next_action');
        $id = $this->actingAs($this->user)->postJson('/premium/subscribe', ['accept_terms' => '1'])->json('data.payment_id');
        $this->actingAs($this->user)->postJson("/premium/subscribe/{$id}/card", ['payment_method_id' => 'pm_card1'])->assertOk()->assertJsonPath('data.redirect', 'https://bank.test/3ds');
    }

    public function test_the_card_is_charged_when_the_trial_ends_and_every_30_days(): void
    {
        $this->trialUser();
        $this->travel(25)->hours();
        Http::fake([
            'api.paymongo.com/v1/payment_intents' => Http::response(['data' => ['id' => 'pi_r1']]),
            'api.paymongo.com/v1/payment_intents/pi_r1/attach' => Http::response(['data' => ['attributes' => ['status' => 'succeeded']]]),
        ]);
        $this->assertSame(['renewed' => 1, 'failed' => 0], app(SubscriptionRenewals::class)->run());
        $user = $this->user->fresh();
        $this->assertSame(['active', 'premium'], [$user->billing_status, Usage::plan($user)]);
        $this->assertEqualsWithDelta(now()->addDays(30)->timestamp, $user->premium_until->timestamp, 5);
        $this->assertSame(1, PremiumPayment::where(['kind' => 'renewal', 'status' => 'paid', 'amount_minor' => 145000])->count());
        Http::assertSent(fn ($r) => $r->url() === 'https://api.paymongo.com/v1/payment_intents/pi_r1/attach' && $r['data']['attributes']['payment_method'] === 'pm_card1');
        // Nothing more is due until the month ends.
        $this->assertSame(['renewed' => 0, 'failed' => 0], app(SubscriptionRenewals::class)->run());
    }

    public function test_failed_renewals_retry_daily_then_cancel(): void
    {
        $this->trialUser();
        Http::fake([
            'api.paymongo.com/v1/payment_intents' => Http::response(['data' => ['id' => 'pi_bad']]),
            'api.paymongo.com/v1/payment_intents/pi_bad/attach' => Http::response(['data' => ['attributes' => ['status' => 'awaiting_payment_method', 'last_payment_error' => ['failed_message' => 'Insufficient funds']]]]),
        ]);
        foreach ([1, 2, 3] as $attempt) {
            $this->travel(25)->hours();
            app(SubscriptionRenewals::class)->run();
            $this->assertSame($attempt, $this->user->fresh()->renewal_attempts);
        }
        $user = $this->user->fresh();
        $this->assertSame(['canceled', false, null], [$user->billing_status, $user->auto_renew, $user->paymongo_payment_method_id]);
        $this->assertFalse(\App\Support\Premium::active($user));
        $this->assertSame('free', Usage::plan($user));
    }

    public function test_cancelling_keeps_premium_until_the_end_and_stops_charges(): void
    {
        $this->trialUser();
        $this->actingAs($this->user)->post('/premium/cancel')->assertRedirect();
        $user = $this->user->fresh();
        $this->assertSame([false, 'canceled', null], [$user->auto_renew, $user->billing_status, $user->paymongo_payment_method_id]);
        $this->assertTrue(\App\Support\Premium::active($user));
        $this->assertSame('trial', Usage::plan($user));
        $this->travel(25)->hours();
        Http::fake();
        $this->assertSame(['renewed' => 0, 'failed' => 0], app(SubscriptionRenewals::class)->run());
        Http::assertNothingSent();
        $this->assertFalse(\App\Support\Premium::active($user->fresh()));
    }

    public function test_allowances_limit_navigation_and_ai_by_plan(): void
    {
        config(['services.premium.allowances.free.navigation' => 2, 'services.premium.allowances.premium.ai_plan' => 0]);
        $start = fn () => $this->actingAs($this->user)->postJson('/api/v1/navigation/start')->assertOk()->json('data');
        $first = $start();
        $this->assertSame([true, 1, 2], [$first['allowed'], $first['remaining'], $first['limit']]);
        $second = $start();
        $this->assertSame([true, 0], [$second['allowed'], $second['remaining']]);
        $third = $start();
        $this->assertFalse($third['allowed']);
        $this->assertStringContainsString('Google Maps', $third['message']);
        $this->assertSame(2, Usage::used($this->user, 'navigation'));

        // Premium AI is capped by the monthly allowance (429), checked before Google is called.
        $this->user->forceFill(['premium_until' => now()->addDays(10)])->save();
        $trip = Trip::create(['organizer_user_id' => $this->user->id, 'name' => 'Vigan', 'destination' => 'Vigan', 'starts_on' => '2026-12-04', 'ends_on' => '2026-12-07', 'timezone' => 'Asia/Manila', 'currency_code' => 'PHP']);
        $trip->members()->create(['user_id' => $this->user->id, 'status' => 'active']);
        Http::fake();
        $this->actingAs($this->user)->postJson("/trips/{$trip->id}/itinerary/suggestions", ['prompt' => 'Heritage walk'])->assertStatus(429);
        Http::assertNothingSent();

        // The app owner is never limited; allowances reset each month.
        config(['services.premium.owner_emails' => $this->user->email]);
        $this->assertNull(Usage::summary($this->user)['ai_plan']['limit']);
        config(['services.premium.owner_emails' => '']);
        $this->travelTo(now('Asia/Manila')->startOfMonth()->addMonth()->addHour());
        $this->assertSame(0, Usage::used($this->user, 'navigation'));
    }

    public function test_free_users_get_free_photos_once_their_google_lookups_run_out(): void
    {
        config(['services.google_maps.browser_key' => 'maps-key', 'services.premium.allowances.free.place_photos' => 0]);
        $trip = Trip::create(['organizer_user_id' => $this->user->id, 'name' => 'Siquijor', 'destination' => 'Siquijor', 'starts_on' => '2026-11-11', 'ends_on' => '2026-11-12', 'timezone' => 'Asia/Manila']);
        $trip->members()->create(['user_id' => $this->user->id, 'status' => 'active']);
        $item = $trip->itineraryItems()->create(['title' => 'Cambugahay Falls', 'starts_at' => now(), 'ends_at' => now()->addHour(), 'timezone' => 'Asia/Manila', 'created_by_user_id' => $this->user->id]);
        Http::fake(['commons.wikimedia.org/*' => Http::response(['query' => ['pages' => ['1' => ['index' => 1, 'imageinfo' => [['mime' => 'image/jpeg', 'url' => 'https://upload.wikimedia.org/a.jpg']]]]]])]);
        $photos = $this->actingAs($this->user)->getJson("/trips/{$trip->id}/itinerary/{$item->id}/photos")->assertOk()->json('data');
        $this->assertSame(['https://upload.wikimedia.org/a.jpg'], array_column($photos, 'url'));
        Http::assertNotSent(fn ($r) => str_contains($r->url(), 'googleapis.com'));
    }

    public function test_homepage_shows_the_plans_and_the_legal_pages_render(): void
    {
        $this->withoutVite();
        $this->get('/')->assertOk()->assertInertia(fn (Assert $page) => $page->component('Landing')
            ->where('plans.price_label', '₱1,450.00')->where('plans.trial_days', 1)->has('plans.free.navigation')->has('plans.premium.ai_plan'));
        $this->get('/terms')->assertOk()->assertInertia(fn (Assert $page) => $page->component('legal/Terms'));
        $this->get('/privacy')->assertOk()->assertInertia(fn (Assert $page) => $page->component('legal/Privacy'));
        $this->get('/subscription-terms')->assertOk()->assertInertia(fn (Assert $page) => $page->component('legal/Subscription')->where('plans.days', 30));
    }
}
