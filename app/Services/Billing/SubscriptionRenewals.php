<?php

namespace App\Services\Billing;

use App\Models\{PremiumPayment, User};
use App\Support\Premium;
use Illuminate\Support\Facades\{DB, Log};

/**
 * Charges subscribers' saved cards when their trial or month ends (run by the scheduler: `premium:renew`, hourly).
 * A failed charge is retried once a day; after MAX_ATTEMPTS failures the subscription is cancelled. Premium is never
 * extended without a successful payment, so an unpaid account simply drops to the free plan.
 */
class SubscriptionRenewals
{
    public const MAX_ATTEMPTS = 3;

    public function __construct(private PayMongoBilling $billing) {}

    /** Renews every subscription that is due. @return array{renewed: int, failed: int} */
    public function run(): array
    {
        $counts = ['renewed' => 0, 'failed' => 0];
        // Charges that were still processing last time.
        PremiumPayment::where('kind', 'renewal')->where('status', 'processing')->whereNotNull('payment_intent_id')->each(function (PremiumPayment $payment) use (&$counts) {
            $status = $this->billing->intent($payment->payment_intent_id)['status'];
            if ($status === 'succeeded') { $this->grant($payment); $counts['renewed']++; }
            elseif (! in_array($status, ['processing', 'awaiting_next_action'], true)) { $payment->update(['status' => 'failed', 'failure_reason' => $status]); }
        });
        User::where('auto_renew', true)->whereNotNull('paymongo_payment_method_id')
            ->where(fn ($q) => $q->whereNull('premium_until')->orWhere('premium_until', '<=', now()->addMinutes(15)))
            ->where(fn ($q) => $q->whereNull('next_renewal_attempt_at')->orWhere('next_renewal_attempt_at', '<=', now()))
            ->whereDoesntHave('premiumPayments', fn ($q) => $q->where('kind', 'renewal')->where('status', 'processing'))
            ->each(function (User $user) use (&$counts) { $this->renew($user) ? $counts['renewed']++ : $counts['failed']++; });
        return $counts;
    }

    /** Charges one month now. True when it was paid (Premium then runs 30 more days). */
    public function renew(User $user): bool
    {
        if (! $user->auto_renew || ! $user->paymongo_payment_method_id) { return false; }
        $payment = PremiumPayment::create(['user_id' => $user->id, 'kind' => 'renewal', 'amount_minor' => Premium::priceMinor(), 'currency_code' => Premium::currency()]);
        try {
            $result = $this->billing->chargeSavedCard($user, Premium::priceMinor(), 'BarkadaKasKas Premium – 30 days', route('premium.renewal.return', $payment));
        } catch (\Throwable $e) {
            Log::warning('Premium renewal: PayMongo unavailable', ['user' => $user->id, 'error' => $e->getMessage()]);
            $result = ['intent' => null, 'status' => 'failed', 'redirect' => null, 'error' => 'The payment provider didn’t answer'];
        }
        $payment->update(['payment_intent_id' => $result['intent']]);
        if ($result['status'] === 'succeeded') { $this->grant($payment); return true; }
        if ($result['status'] === 'processing') {
            $payment->update(['status' => 'processing']);
            $user->forceFill(['next_renewal_attempt_at' => now()->addHour()])->save();
            return false;
        }
        // Declined, or the bank wants the cardholder to confirm (3-D Secure) — which can't happen without them.
        $needsAction = $result['status'] === 'awaiting_next_action';
        $payment->update(['status' => 'failed', 'failure_reason' => $needsAction ? 'Your bank asked you to confirm the payment' : ($result['error'] ?? $result['status'])]);
        $attempts = $user->renewal_attempts + 1;
        $giveUp = $attempts >= self::MAX_ATTEMPTS;
        $user->forceFill(['renewal_attempts' => $attempts, 'billing_status' => $giveUp ? 'canceled' : 'past_due', 'next_renewal_attempt_at' => $giveUp ? null : now()->addDay()]
            + ($giveUp ? ['auto_renew' => false, 'paymongo_payment_method_id' => null] : []))->save();
        SubscriptionMail::paymentFailed($user, (string) $payment->failure_reason, $giveUp, $needsAction ? $result['redirect'] : null);
        return false;
    }

    /** Marks a renewal paid (once) and adds 30 days from the end of the current Premium (or from now). */
    public function grant(PremiumPayment $payment): void
    {
        $user = DB::transaction(function () use ($payment) {
            $payment = PremiumPayment::whereKey($payment->id)->lockForUpdate()->first();
            if (! $payment || $payment->status === 'paid') { return null; }
            $user = User::whereKey($payment->user_id)->lockForUpdate()->firstOrFail();
            $from = $user->premium_until && $user->premium_until->isFuture() ? $user->premium_until : now();
            $until = $from->copy()->addDays(30);
            $user->forceFill(['premium_until' => $until, 'billing_status' => 'active', 'renewal_attempts' => 0, 'next_renewal_attempt_at' => null])->save();
            $payment->update(['status' => 'paid', 'paid_at' => now(), 'period_end' => $until, 'failure_reason' => null]);
            return $user;
        });
        if ($user) { SubscriptionMail::renewed($user); }
    }
}
