<?php

namespace App\Policies;

use App\Models\{Trip, User};

class TripPolicy
{
    public function view(User $user, Trip $trip): bool
    {
        return $trip->members()->where('user_id', $user->id)->where('status', 'active')->exists();
    }

    public function update(User $user, Trip $trip): bool
    {
        return $trip->organizer_user_id === $user->id;
    }
}
