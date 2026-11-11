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

    /**
     * What each other traveller and [$viewerId] owe each other, with the bills and repayments behind it. On each recorded
     * bill, whoever paid the merchant more than their own share fronted money for those who paid less; each person's
     * shortfall is split across the people who fronted it, in proportion to how much each fronted. (Money nobody has
     * paid the merchant yet is owed to the merchant, not to a traveller, so it isn't counted here.) Confirmed
     * repayments between the two then reduce it; pending ones are listed but not counted until confirmed.
     * net_minor > 0: they owe the viewer; < 0: the viewer owes them.
     */
    public function between(Trip $trip, ?int $viewerId): array
    {
        if (! $viewerId) { return []; }
        $members = $trip->members()->with('user:id,name')->get()->keyBy('id');
        $lines = $members->keys()->reject(fn ($id) => $id === $viewerId)->mapWithKeys(fn ($id) => [$id => []])->all();
        $expenses = $trip->expenses()->whereIn('status', ['open', 'completed', 'closed'])->with(['payments', 'allocations'])->orderBy('created_at')->get();
        $money = fn (int $minor) => \App\Services\Alerts::money($minor, $trip->currency_code);
        foreach ($expenses as $expense) {
            $paid = []; $share = [];
            foreach ($expense->payments as $payment) { if ($payment->paid_at) { $paid[$payment->trip_member_id] = ($paid[$payment->trip_member_id] ?? 0) + $payment->amount_minor; } }
            foreach ($expense->allocations as $allocation) { $share[$allocation->trip_member_id] = ($share[$allocation->trip_member_id] ?? 0) + $allocation->amount_minor; }
            $ids = array_unique([...array_keys($paid), ...array_keys($share)]);
            $fronted = []; $short = [];
            foreach ($ids as $id) {
                $diff = ($paid[$id] ?? 0) - ($share[$id] ?? 0);
                if ($diff > 0) { $fronted[$id] = $diff; } elseif ($diff < 0) { $short[$id] = -$diff; }
            }
            $shortTotal = array_sum($short);
            if (! $shortTotal || ! $fronted) { continue; }
            $date = $expense->incurred_at ? \Carbon\CarbonImmutable::parse($expense->incurred_at)->format('M j') : null;
            // They owe the viewer (viewer fronted, they were short) or the viewer owes them.
            foreach ([1, -1] as $sign) {
                foreach ($lines as $other => $_) {
                    [$from, $to] = $sign > 0 ? [$other, $viewerId] : [$viewerId, $other];
                    if (! isset($short[$from], $fronted[$to])) { continue; }
                    $amount = (int) round($short[$from] * $fronted[$to] / $shortTotal);
                    if ($amount === 0) { continue; }
                    $lines[$other][] = [
                        'type' => 'expense', 'expense_id' => $expense->id, 'label' => $expense->title, 'date' => $date, 'pending' => false,
                        'detail' => $sign > 0
                            ? 'Their share '.$money($share[$from] ?? 0).' · you paid '.$money($paid[$to]).' of '.$money($expense->total_minor)
                            : 'Your share '.$money($share[$from] ?? 0).' · they paid '.$money($paid[$to]).' of '.$money($expense->total_minor),
                        'amount_minor' => $sign * $amount,
                    ];
                }
            }
        }
        $settlements = $trip->settlements()->whereIn('status', ['pending', 'confirmed'])
            ->where(fn ($q) => $q->where('from_member_id', $viewerId)->orWhere('to_member_id', $viewerId))
            ->where(fn ($q) => $q->whereNull('expense_id')->orWhereHas('expense', fn ($e) => $e->whereIn('status', ['open', 'completed', 'closed'])))
            ->with('expense:id,title')->orderBy('created_at')->get();
        foreach ($settlements as $settlement) {
            $theyPaid = $settlement->to_member_id === $viewerId;
            $other = $theyPaid ? $settlement->from_member_id : $settlement->to_member_id;
            if (! array_key_exists($other, $lines)) { continue; }
            $lines[$other][] = [
                'type' => 'payment', 'expense_id' => $settlement->expense_id, 'pending' => $settlement->status === 'pending',
                'label' => $theyPaid ? 'They paid you' : 'You paid them', 'date' => $settlement->paid_at ? \Carbon\CarbonImmutable::parse($settlement->paid_at)->format('M j') : null,
                'detail' => ($settlement->expense ? 'For '.$settlement->expense->title : 'Trip reimbursement').($settlement->status === 'pending' ? ' · waiting for confirmation' : ''),
                // Repayments to the viewer reduce what they owe; the viewer's repayments reduce what the viewer owes.
                'amount_minor' => $theyPaid ? -$settlement->amount_minor : $settlement->amount_minor,
            ];
        }
        return collect($lines)->map(fn ($items, $id) => [
            'member_id' => $id, 'name' => $members[$id]->user->name,
            'net_minor' => (int) collect($items)->where('pending', false)->sum('amount_minor'),
            'lines' => $items,
        ])->values()->all();
    }

    /** What recorded bills still owe their merchants (totals minus payments made), across the trip. */
    public function unpaidToMerchants(Trip $trip): int
    {
        return (int) $trip->expenses()->whereIn('status', ['open', 'completed', 'closed'])->with('payments')->get()
            ->sum(fn ($expense) => max(0, $expense->total_minor - (int) $expense->payments->whereNotNull('paid_at')->sum('amount_minor')));
    }
}
