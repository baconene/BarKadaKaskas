<?php

namespace App\Support;

use App\Models\User;
use Illuminate\Support\Facades\DB;
use Symfony\Component\HttpKernel\Exception\HttpException;

/**
 * Monthly allowances for the features that cost money per use (Google AI and Maps). Each user gets the allowance of
 * their plan (Premium, trial or free) for the calendar month in Manila; see config('services.premium.allowances').
 * Check with allows()/remaining() before the paid call and record() after it worked, so failures don't use it up.
 * The app owner (PREMIUM_OWNER_EMAILS) is never limited.
 */
class Usage
{
    public const FEATURES = ['ai_plan', 'receipt_scan', 'ai_image', 'ai_insight', 'navigation', 'place_photos'];

    private const LABELS = [
        'ai_plan' => 'AI trip planner requests', 'receipt_scan' => 'AI receipt scans', 'ai_image' => 'AI photo edits',
        'ai_insight' => 'AI tips and day prep', 'navigation' => 'in-app navigations', 'place_photos' => 'place photo lookups',
    ];

    public static function plan(?User $user): string
    {
        if (! Premium::active($user)) { return 'free'; }
        return Premium::onTrial($user) ? 'trial' : 'premium';
    }

    public static function limit(?User $user, string $feature): int
    {
        if (Premium::owner($user)) { return PHP_INT_MAX; }
        return (int) config('services.premium.allowances.'.self::plan($user).'.'.$feature, 0);
    }

    public static function used(?User $user, string $feature): int
    {
        if (! $user) { return 0; }
        return (int) DB::table('usage_counters')->where(['user_id' => $user->id, 'feature' => $feature, 'period' => self::period()])->value('used');
    }

    public static function remaining(?User $user, string $feature): int
    {
        if (! $user) { return 0; }
        $limit = self::limit($user, $feature);
        return $limit === PHP_INT_MAX ? PHP_INT_MAX : max(0, $limit - self::used($user, $feature));
    }

    public static function allows(?User $user, string $feature): bool
    {
        return self::remaining($user, $feature) > 0;
    }

    /** Counts one use (or [count]) this month. */
    public static function record(?User $user, string $feature, int $count = 1): void
    {
        if (! $user || $count < 1) { return; }
        $key = ['user_id' => $user->id, 'feature' => $feature, 'period' => self::period()];
        // The row may not exist yet this month; the unique index makes the insert safe when two requests race.
        DB::table('usage_counters')->insertOrIgnore($key + ['used' => 0, 'created_at' => now(), 'updated_at' => now()]);
        DB::table('usage_counters')->where($key)->increment('used', $count, ['updated_at' => now()]);
    }

    /** Uses one if there is one left: true when it was counted. For features that are paid the moment they start. */
    public static function take(?User $user, string $feature): bool
    {
        if (! self::allows($user, $feature)) { return false; }
        self::record($user, $feature);
        return true;
    }

    /** Stops the request (429) with a readable message when this month's allowance is used up. */
    public static function require(?User $user, string $feature): void
    {
        if (self::allows($user, $feature)) { return; }
        throw new HttpException(429, self::message($user, $feature));
    }

    public static function message(?User $user, string $feature): string
    {
        $label = self::LABELS[$feature] ?? $feature;
        $limit = self::limit($user, $feature);
        $reset = now('Asia/Manila')->startOfMonth()->addMonth()->format('F j');
        return match (self::plan($user)) {
            'free' => $limit > 0 ? "You’ve used this month’s {$limit} free {$label}. They reset on {$reset}; Premium includes more." : "This needs Premium.",
            'trial' => "You’ve used the {$limit} {$label} included in the free trial. Your full Premium allowance starts when the trial ends.",
            default => "You’ve used this month’s {$limit} {$label}. They reset on {$reset}.",
        };
    }

    /** Every allowance for the apps: [feature => [limit, used, remaining]] (null limit = unlimited). */
    public static function summary(?User $user): array
    {
        $out = [];
        foreach (self::FEATURES as $feature) {
            $limit = self::limit($user, $feature); $used = self::used($user, $feature);
            $unlimited = $limit === PHP_INT_MAX;
            $out[$feature] = ['label' => self::LABELS[$feature], 'limit' => $unlimited ? null : $limit, 'used' => $used, 'remaining' => $unlimited ? null : max(0, $limit - $used)];
        }
        return $out;
    }

    public static function period(): string
    {
        return now('Asia/Manila')->format('Y-m');
    }
}
