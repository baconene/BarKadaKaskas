<?php

namespace App\Policies;

use App\Models\{Trip, User};
use Illuminate\Auth\Access\Response;

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

    /** Organizer changes to the plan (itinerary, invitations, expense edits); locked once the trip is cancelled. */
    public function plan(User $user, Trip $trip): Response
    {
        if (! $this->update($user, $trip)) { return Response::deny(); }
        return $trip->isCancelled() ? Response::deny('This trip was cancelled. Reopen it to change the plan.') : Response::allow();
    }

    /** New shared expenses; settling up existing ones stays possible after a cancellation. */
    public function addExpense(User $user, Trip $trip): Response
    {
        if (! $this->view($user, $trip)) { return Response::deny(); }
        return $trip->isCancelled() ? Response::deny('This trip was cancelled, so new expenses cannot be added. You can still settle up.') : Response::allow();
    }
}
