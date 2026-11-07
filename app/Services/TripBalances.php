<?php

namespace App\Services;

use App\Models\Trip;

class TripBalances
{
    public function calculate(Trip $trip): array
    {
        $balances = $trip->members()->with('user')->get()->mapWithKeys(fn ($member) => [$member->id => ['member_id' => $member->id, 'name' => $member->user->name, 'net_minor' => 0]])->all();
        foreach ($trip->expenses()->whereIn('status', ['completed', 'closed'])->with(['payments', 'allocations'])->get() as $expense) {
            foreach ($expense->payments as $payment) { $balances[$payment->trip_member_id]['net_minor'] += $payment->amount_minor; }
            foreach ($expense->allocations as $allocation) { $balances[$allocation->trip_member_id]['net_minor'] -= $allocation->amount_minor; }
        }
        foreach ($trip->settlements()->where('status', 'confirmed')->get() as $settlement) {
            $balances[$settlement->from_member_id]['net_minor'] += $settlement->amount_minor;
            $balances[$settlement->to_member_id]['net_minor'] -= $settlement->amount_minor;
        }
        return array_values($balances);
    }
}
