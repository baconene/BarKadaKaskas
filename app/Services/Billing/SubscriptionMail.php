<?php

namespace App\Services\Billing;

use App\Models\User;
use App\Support\Premium;
use Illuminate\Support\Facades\{Log, Mail};

/** Plain e-mails about the subscription: what was agreed, each charge, failures and cancellation. */
class SubscriptionMail
{
    public static function trialStarted(User $user): void
    {
        $end = self::when($user->premium_until);
        self::send($user, 'Your BarkadaKasKas Premium free trial has started', [
            "Your free Premium trial is on until {$end} (Manila time).",
            'When it ends, your saved card ('.($user->card_last4 ? '•••• '.$user->card_last4 : 'card').') will be charged '.Premium::priceLabel().' and again every 30 days until you cancel.',
            'Don’t want to continue? Cancel any time before '.$end.' in Settings → Premium and you won’t be charged.',
        ]);
    }

    public static function renewed(User $user): void
    {
        self::send($user, 'BarkadaKasKas Premium renewed', [
            'We charged '.Premium::priceLabel().' to your saved card for 30 more days of Premium.',
            'Premium now runs until '.self::when($user->premium_until).'. You can cancel any time in Settings → Premium; it stays on until then and isn’t charged again.',
        ]);
    }

    public static function paymentFailed(User $user, string $reason, bool $cancelled, ?string $confirmUrl): void
    {
        self::send($user, $cancelled ? 'Your BarkadaKasKas Premium subscription was cancelled' : 'We couldn’t renew your BarkadaKasKas Premium', array_filter([
            'The payment of '.Premium::priceLabel().' didn’t go through: '.$reason.'.',
            $confirmUrl ? 'Your bank needs you to confirm it: '.$confirmUrl : null,
            $cancelled ? 'After several tries the subscription was cancelled. Your account is on the free plan; you can subscribe again in Settings → Premium.'
                : 'We’ll try again in about a day. You can also pay for 30 days now with GCash, Maya or card in Settings → Premium.',
        ]));
    }

    public static function canceled(User $user): void
    {
        self::send($user, 'Your BarkadaKasKas Premium subscription is cancelled', [
            'Automatic renewal is off and your card won’t be charged again.',
            $user->premium_until?->isFuture() ? 'Premium stays on until '.self::when($user->premium_until).'.' : 'Your account is on the free plan.',
        ]);
    }

    private static function when($time): string
    {
        return $time ? $time->copy()->setTimezone('Asia/Manila')->format('M j, Y g:i A') : '—';
    }

    private static function send(User $user, string $subject, array $lines): void
    {
        try {
            Mail::raw('Hi '.\App\Services\Alerts::firstName($user).",\n\n".implode("\n\n", $lines)."\n\nTerms: ".route('legal.subscription')."\n\n— BarkadaKasKas", fn ($m) => $m->to($user->email)->subject($subject));
        } catch (\Throwable $e) {
            Log::warning('Subscription e-mail not sent', ['user' => $user->id, 'error' => $e->getMessage()]);
        }
    }
}
