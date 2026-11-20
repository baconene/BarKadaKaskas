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

    public static function priceMinor(): int { return (int) config('services.premium.price_minor', 145000); }
    public static function currency(): string { return (string) config('services.premium.currency', 'PHP'); }
    public static function priceLabel(): string { return \App\Services\Alerts::money(self::priceMinor(), self::currency()); }

    /** What the apps show about the signed-in user's Premium. */
    public static function status(?User $user): array
    {
        return ['active' => self::active($user), 'owner' => self::owner($user), 'until' => $user?->premium_until?->toIso8601String(),
            'price_minor' => self::priceMinor(), 'currency' => self::currency(), 'price_label' => self::priceLabel(), 'days' => 30,
            'checkout_available' => (bool) config('services.paymongo.secret_key')];
    }

    /** @return list<string> */
    private static function owners(): array
    {
        return array_values(array_filter(array_map(fn ($e) => mb_strtolower(trim($e)), explode(',', (string) config('services.premium.owner_emails')))));
    }
}
