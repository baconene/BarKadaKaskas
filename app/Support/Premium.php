<?php

namespace App\Support;

use App\Models\{Trip, User};
use Symfony\Component\HttpKernel\Exception\HttpException;

/**
 * Premium (paid monthly through PayMongo) unlocks the AI features. Only a trip's organizer can use AI on that trip,
 * and only while Premium. Joiners can join and create trips, but AI stays off until they go Premium and organize.
 * The app owner's accounts (PREMIUM_OWNER_EMAILS) always count as Premium.
 */
class Premium
{
    public static function active(?User $user): bool
    {
        if (! $user) { return false; }
        if (in_array(mb_strtolower($user->email), self::owners(), true)) { return true; }
        return $user->premium_until !== null && $user->premium_until->isFuture();
    }

    public static function owner(?User $user): bool
    {
        return $user !== null && in_array(mb_strtolower($user->email), self::owners(), true);
    }

    /** Whether this user may use AI (on this trip, when given). */
    public static function allows(?User $user, ?Trip $trip = null): bool
    {
        return self::active($user) && ($trip === null || $trip->organizer_user_id === $user?->id);
    }

    /** Stops the request with a readable message when AI isn't allowed. */
    public static function require(?User $user, ?Trip $trip = null): void
    {
        if (self::allows($user, $trip)) { return; }
        throw new HttpException(402, $trip && $trip->organizer_user_id !== $user?->id
            ? 'AI features are for the trip’s organizer with Premium. Go Premium and organize your own trip to use AI.'
            : 'AI features need Premium ('.self::priceLabel().' a month). Go Premium in Settings → Premium.');
    }

    /** Premium only through the free trial (no paid month yet). */
    public static function onTrial(?User $user): bool
    {
        if (! $user?->trial_started_at || ! $user->premium_until || ! self::active($user) || self::owner($user)) { return false; }
        return $user->premium_until->lte($user->trial_started_at->copy()->addDays(self::trialDays())->addMinutes(5));
    }

    /** Whether this user can still start the free trial: once per person, and not after having paid. */
    public static function trialAvailable(?User $user): bool
    {
        return $user !== null && self::trialDays() > 0 && ! $user->trial_started_at && ! self::active($user)
            && ! \App\Models\PremiumPayment::where('user_id', $user->id)->where('status', 'paid')->exists();
    }

    public static function trialDays(): int { return (int) config('services.premium.trial_days', 1); }

    public static function priceMinor(): int { return (int) config('services.premium.price_minor', 145000); }
    public static function currency(): string { return (string) config('services.premium.currency', 'PHP'); }
    public static function priceLabel(): string { return \App\Services\Alerts::money(self::priceMinor(), self::currency()); }

    /** The plans as the homepage shows them: price, trial and each plan's monthly allowances. */
    public static function plans(): array
    {
        return ['price_label' => self::priceLabel(), 'price_minor' => self::priceMinor(), 'currency' => self::currency(), 'days' => 30, 'trial_days' => self::trialDays(),
            'free' => config('services.premium.allowances.free'), 'premium' => config('services.premium.allowances.premium'), 'trial' => config('services.premium.allowances.trial')];
    }

    /** What the apps show about the signed-in user's Premium. */
    public static function status(?User $user): array
    {
        return ['active' => self::active($user), 'owner' => self::owner($user), 'until' => $user?->premium_until?->toIso8601String(),
            'price_minor' => self::priceMinor(), 'currency' => self::currency(), 'price_label' => self::priceLabel(), 'days' => 30,
            'checkout_available' => (bool) config('services.paymongo.secret_key'),
            // Subscription: a saved card charged every 30 days until cancelled, starting with a free trial once.
            'subscription_available' => app(\App\Services\Billing\PayMongoBilling::class)->configured(),
            'trial' => self::onTrial($user), 'trial_available' => self::trialAvailable($user), 'trial_days' => self::trialDays(),
            'billing_status' => $user?->billing_status ?? 'none', 'auto_renew' => (bool) $user?->auto_renew,
            'card' => $user?->card_last4 ? trim(($user->card_brand ? ucfirst($user->card_brand).' ' : '').'•••• '.$user->card_last4) : null,
            'plan' => \App\Support\Usage::plan($user), 'usage' => \App\Support\Usage::summary($user)];
    }

    /** @return list<string> */
    private static function owners(): array
    {
        return array_values(array_filter(array_map(fn ($e) => mb_strtolower(trim($e)), explode(',', (string) config('services.premium.owner_emails')))));
    }
}
