<?php

namespace App\Services;

use App\Models\Trip;

/**
 * Each traveller's net position: what they paid merchants and received as reimbursements, minus their
 * assigned shares and what they repaid. Every recorded bill (open, completed or closed) counts in full as
 * a forecast, even before the merchant is paid: travellers see their whole share, while payers are credited
 * only with what they have actually paid. Until a bill is fully paid, the balances therefore don't add up
 * to zero; the difference is what is still owed to merchants (see unpaidToMerchants).
 */
class TripBalances
{
    public function calculate(Trip $trip): array
    {
        $balances = $trip->members()->with('user')->get()->mapWithKeys(fn ($member) => [$member->id => ['member_id' => $member->id, 'name' => $member->user->name, 'net_minor' => 0]])->all();
        foreach ($trip->expenses()->whereIn('status', ['open', 'completed', 'closed'])->with(['payments', 'allocations'])->get() as $expense) {
            foreach ($expense->payments as $payment) { if ($payment->paid_at) { $balances[$payment->trip_member_id]['net_minor'] += $payment->amount_minor; } }
            foreach ($expense->allocations as $allocation) { $balances[$allocation->trip_member_id]['net_minor'] -= $allocation->amount_minor; }
        }
        foreach ($trip->settlements()->where('status', 'confirmed')->where(fn ($q) => $q->whereNull('expense_id')->orWhereHas('expense', fn ($e) => $e->whereIn('status', ['open', 'completed', 'closed'])))->get() as $settlement) {
            $balances[$settlement->from_member_id]['net_minor'] += $settlement->amount_minor;
            $balances[$settlement->to_member_id]['net_minor'] -= $settlement->amount_minor;
        }
        return array_values($balances);
    }

    /**
     * Per traveller, the two sides that the net balance hides: what they still have to pay for their own shares
     * (owes_minor) and what others still have to pay back to them on bills they covered (owed_minor). Uses the
     * same per-bill share rows as the expense cards ([$shareRows] keyed by expense id), on recorded bills only.
     */
    public function dues(Trip $trip, array $shareRows): array
    {
        $dues = $trip->members()->pluck('id')->mapWithKeys(fn ($id) => [$id => ['member_id' => $id, 'owes_minor' => 0, 'owed_minor' => 0]])->all();
        foreach ($trip->expenses as $expense) {
            if (! in_array($expense->status->value, ['open', 'completed', 'closed'], true)) { continue; }
            foreach ($shareRows[$expense->id] ?? [] as $row) {
                if (! isset($dues[$row['member_id']])) { continue; }
                $dues[$row['member_id']]['owes_minor'] += $row['remaining_minor'];
                $dues[$row['member_id']]['owed_minor'] += max(0, $row['covered_minor'] - $row['share_minor']);
            }
        }
        return array_values($dues);
    }

    /** What recorded bills still owe their merchants (totals minus payments made), across the trip. */
    public function unpaidToMerchants(Trip $trip): int
    {
        return (int) $trip->expenses()->whereIn('status', ['open', 'completed', 'closed'])->with('payments')->get()
            ->sum(fn ($expense) => max(0, $expense->total_minor - (int) $expense->payments->whereNotNull('paid_at')->sum('amount_minor')));
    }
}
