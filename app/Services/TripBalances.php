<?php

namespace App\Services;

use App\Models\Trip;

/**
 * Each traveller's net position: what they paid merchants and received as reimbursements, minus their
 * assigned shares and what they repaid. Open expenses count as soon as the merchant has been paid in full
 * and the whole total is assigned, so travellers see what they owe before the organizer completes the bill.
 */
class TripBalances
{
    public function calculate(Trip $trip): array
    {
        $balances = $trip->members()->with('user')->get()->mapWithKeys(fn ($member) => [$member->id => ['member_id' => $member->id, 'name' => $member->user->name, 'net_minor' => 0]])->all();
        $expenses = $trip->expenses()->whereIn('status', ['open', 'completed', 'closed'])->with(['payments', 'allocations'])->get()
            ->filter(fn ($expense) => $expense->status->countsTowardBalance() || self::funded($expense));
        foreach ($expenses as $expense) {
            foreach ($expense->payments as $payment) { if ($payment->paid_at) { $balances[$payment->trip_member_id]['net_minor'] += $payment->amount_minor; } }
            foreach ($expense->allocations as $allocation) { $balances[$allocation->trip_member_id]['net_minor'] -= $allocation->amount_minor; }
        }
        foreach ($trip->settlements()->where('status', 'confirmed')->where(fn ($q) => $q->whereNull('expense_id')->orWhereHas('expense', fn ($e) => $e->whereIn('status', ['open', 'completed', 'closed'])))->get() as $settlement) {
            $balances[$settlement->from_member_id]['net_minor'] += $settlement->amount_minor;
            $balances[$settlement->to_member_id]['net_minor'] -= $settlement->amount_minor;
        }
        return array_values($balances);
    }

    /** An open expense is ready to split once merchant payments and assigned shares both equal its total. */
    public static function funded(\App\Models\Expense $expense): bool
    {
        return (int) $expense->payments->whereNotNull('paid_at')->sum('amount_minor') === $expense->total_minor
            && (int) $expense->allocations->sum('amount_minor') === $expense->total_minor;
    }
}
