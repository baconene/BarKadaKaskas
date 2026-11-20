<?php

namespace App\Http\Controllers;

use App\Models\{PremiumPayment, User};
use App\Services\Billing\{PayMongoBilling, SubscriptionMail};
use App\Support\Premium;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;

/**
 * Premium as a subscription: the subscriber saves a card, gets the free trial once (PREMIUM_TRIAL_DAYS), and is then
 * charged the monthly price every 30 days until they cancel. Cancelling stops the next charge; Premium stays until the
 * end of the time already started (trial or paid month).
 *
 * 1. start: accept the terms; we create a card-check payment intent (a small hold, released at once).
 * 2. card: the browser sends the PayMongo payment method id (made in the browser with the public key); we attach it.
 *    PayMongo may ask for 3-D Secure: the browser goes to its page, which comes back to `back`.
 * 3. finish: the card is saved; with the trial still available it starts now, otherwise the first month is charged now.
 */
class SubscriptionController extends Controller
{
    public function __construct(private PayMongoBilling $billing) {}

    public function start(Request $request)
    {
        abort_unless($this->billing->configured(), 503, 'Card subscriptions aren’t set up yet. You can still pay for 30 days with GCash, Maya or card.');
        $request->validate(['accept_terms' => 'accepted'], ['accept_terms.accepted' => 'Please accept the Terms of Service and Subscription Terms to continue.']);
        $user = $request->user();
        abort_if($user->auto_renew && Premium::active($user), 422, 'Your subscription is already on.');
        $user->forceFill(['terms_accepted_at' => now()])->save();
        $trial = Premium::trialAvailable($user);
        $payment = PremiumPayment::create(['user_id' => $user->id, 'kind' => 'verification', 'amount_minor' => Premium::holdMinor(), 'currency_code' => 'PHP']);
        $intent = $this->billing->cardSetupIntent($user, Premium::holdMinor(), $trial ? 'BarkadaKasKas Premium free trial – card check (released)' : 'BarkadaKasKas Premium – card check (released)');
        $payment->update(['payment_intent_id' => $intent['id']]);
        return response()->json(['data' => [
            'payment_id' => $payment->id, 'public_key' => config('services.paymongo.public_key'), 'trial' => $trial,
            'trial_days' => Premium::trialDays(), 'price_label' => Premium::priceLabel(), 'hold_label' => Premium::holdLabel(),
        ]]);
    }

    public function card(Request $request, PremiumPayment $payment)
    {
        $this->own($request, $payment);
        $data = $request->validate(['payment_method_id' => ['required', 'string', 'max:100', 'regex:/^pm_[A-Za-z0-9]+$/']]);
        $result = $this->billing->attach($payment->payment_intent_id, $data['payment_method_id'], route('premium.subscribe.return', $payment));
        if ($result['status'] === 'awaiting_next_action' && $result['redirect']) { return response()->json(['data' => ['redirect' => $result['redirect']]]); }
        $outcome = $this->finish($payment);
        return response()->json(['data' => $outcome], $outcome['ok'] ? 200 : 422);
    }

    /** Back from PayMongo's 3-D Secure page. */
    public function back(Request $request, PremiumPayment $payment)
    {
        $this->own($request, $payment);
        $outcome = $this->finish($payment);
        return to_route('premium.show')->with('premium', $outcome['ok'] ? ($outcome['trial'] ? 'trial' : 'subscribed') : 'card_failed')->with('premium_message', $outcome['message']);
    }

    /** Back from confirming a renewal charge with the bank (3-D Secure link from the e-mail). */
    public function renewalBack(Request $request, PremiumPayment $payment)
    {
        abort_unless($payment->user_id === $request->user()->id && $payment->kind === 'renewal' && $payment->payment_intent_id, 404);
        $paid = $payment->status === 'paid' || $this->billing->intent($payment->payment_intent_id)['status'] === 'succeeded';
        if ($paid) { app(\App\Services\Billing\SubscriptionRenewals::class)->grant($payment); }
        return to_route('premium.show')->with('premium', $paid ? 'paid' : 'pending');
    }

    /** Stops automatic renewal. Premium stays until the end of the current trial or month; nothing is refunded. */
    public function cancel(Request $request)
    {
        $user = $request->user();
        $user->forceFill(['auto_renew' => false, 'billing_status' => 'canceled', 'paymongo_payment_method_id' => null, 'next_renewal_attempt_at' => null])->save();
        SubscriptionMail::canceled($user);
        $message = $user->premium_until?->isFuture() ? 'Subscription cancelled. Premium stays on until '.$user->premium_until->setTimezone('Asia/Manila')->format('M j, Y g:i A').'; you won’t be charged again.' : 'Subscription cancelled.';
        return $request->is('api/*') || $request->expectsJson() ? response()->json(['data' => Premium::status($user->fresh()), 'message' => $message]) : back()->with('premium', 'canceled')->with('premium_message', $message);
    }

    /** @return array{ok: bool, trial: bool, message: string} */
    private function finish(PremiumPayment $payment): array
    {
        if ($payment->status === 'released') { return ['ok' => true, 'trial' => (bool) $payment->user->trial_started_at, 'message' => 'Your card is saved.']; }
        $intent = $this->billing->intent($payment->payment_intent_id);
        if (! in_array($intent['status'], ['awaiting_capture', 'succeeded'], true) || ! $intent['payment_method']) {
            $payment->update(['status' => 'failed', 'failure_reason' => $intent['error'] ?? $intent['status']]);
            return ['ok' => false, 'trial' => false, 'message' => 'Your card couldn’t be verified'.($intent['error'] ? ': '.$intent['error'] : '').'. Try another card.'];
        }
        // The hold was only a check: release it.
        if ($intent['status'] === 'awaiting_capture') { $this->billing->cancel($payment->payment_intent_id); }
        $card = $this->billing->card($intent['payment_method']);
        $trial = DB::transaction(function () use ($payment, $intent, $card) {
            $payment = PremiumPayment::whereKey($payment->id)->lockForUpdate()->first();
            $user = User::whereKey($payment->user_id)->lockForUpdate()->firstOrFail();
            if ($payment->status === 'released') { return (bool) $user->trial_started_at; }
            $payment->update(['status' => 'released']);
            $trial = Premium::trialAvailable($user);
            $user->forceFill(['paymongo_payment_method_id' => $intent['payment_method'], 'card_brand' => $card['brand'], 'card_last4' => $card['last4'],
                'auto_renew' => true, 'renewal_attempts' => 0, 'next_renewal_attempt_at' => null] + ($trial
                ? ['billing_status' => 'trialing', 'trial_started_at' => now(), 'premium_until' => now()->addDays(Premium::trialDays())]
                : ['billing_status' => $user->premium_until?->isFuture() ? 'active' : 'past_due']))->save();
            return $trial;
        });
        $user = $payment->user()->first();
        if ($trial) {
            SubscriptionMail::trialStarted($user);
            return ['ok' => true, 'trial' => true, 'message' => 'Your free trial is on until '.$user->premium_until->setTimezone('Asia/Manila')->format('M j, g:i A').'. After that your card is charged '.Premium::priceLabel().' every 30 days until you cancel.'];
        }
        // No trial left: if Premium already runs (a month paid by hand), the card is charged when it ends; otherwise now.
        if ($user->premium_until?->isFuture()) {
            return ['ok' => true, 'trial' => false, 'message' => 'Your card is saved. It will be charged '.Premium::priceLabel().' when your current Premium ends on '.$user->premium_until->setTimezone('Asia/Manila')->format('M j').'.'];
        }
        $charged = app(\App\Services\Billing\SubscriptionRenewals::class)->renew($user);
        return $charged
            ? ['ok' => true, 'trial' => false, 'message' => 'You’re subscribed. '.Premium::priceLabel().' was charged; the next charge is in 30 days.']
            : ['ok' => false, 'trial' => false, 'message' => 'Your card is saved, but the first payment didn’t go through. Try again from Settings → Premium.'];
    }

    private function own(Request $request, PremiumPayment $payment): void
    {
        abort_unless($payment->user_id === $request->user()->id && $payment->kind === 'verification' && $payment->payment_intent_id, 404);
    }
}
