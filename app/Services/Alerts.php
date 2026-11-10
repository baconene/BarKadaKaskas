<?php

namespace App\Services;

use App\Models\{Alert, Trip, User};

/**
 * Creates alerts for the people on a trip when someone else does something there. The actor never gets an alert for
 * their own action. Alerts are read through /alerts (the web bell, the mobile apps and their background checks).
 */
class Alerts
{
    /**
     * @param  list<int>|null  $userIds  Only these users (still limited to active members, minus the actor); null for everyone on the trip.
     */
    public function notify(Trip $trip, User $actor, string $type, string $title, ?string $body = null, array $data = [], ?array $userIds = null): void
    {
        $recipients = $trip->members()->where('status', 'active')->where('user_id', '!=', $actor->id)
            ->when($userIds !== null, fn ($q) => $q->whereIn('user_id', $userIds))
            ->pluck('user_id')->unique();
        $now = now();
        $rows = $recipients->map(fn ($userId) => [
            'user_id' => $userId, 'trip_id' => $trip->id, 'actor_user_id' => $actor->id, 'type' => $type,
            'title' => mb_substr($title, 0, 200), 'body' => $body !== null ? mb_substr($body, 0, 500) : null,
            'data' => json_encode($data + ['trip_id' => $trip->id]), 'created_at' => $now, 'updated_at' => $now,
        ])->all();
        if ($rows) { Alert::insert($rows); }
    }

    public static function money(int $minor, string $currency): string
    {
        return ($currency === 'PHP' ? '₱' : $currency.' ').number_format($minor / 100, 2);
    }

    public static function firstName(User $user): string
    {
        return strtok(trim($user->name), ' ') ?: 'Someone';
    }
}
