<?php

namespace App\Services;

use App\Enums\ExpenseStatus;
use App\Models\{ActivityLog, Expense, Settlement, Trip, User};
use Illuminate\Support\Facades\{DB, Gate};
use Illuminate\Validation\ValidationException;

class ExpenseShares
{
    public function rows(Expense $expense): array
    {
        $expense->loadMissing(['allocations', 'payments', 'settlements']);
        $rows = [];
        foreach ($expense->allocations as $allocation) {
            $id = $allocation->trip_member_id;
            $rows[$id] ??= ['member_id' => $id, 'share_minor' => 0, 'covered_minor' => 0];
            $rows[$id]['share_minor'] += $allocation->amount_minor;
        }
        foreach ($expense->payments as $payment) {
            $id = $payment->trip_member_id;
            $rows[$id] ??= ['member_id' => $id, 'share_minor' => 0, 'covered_minor' => 0];
            if ($payment->paid_at) $rows[$id]['covered_minor'] += $payment->amount_minor;
        }
        foreach ($expense->settlements->where('status', 'confirmed') as $settlement) {
            foreach ([$settlement->from_member_id, $settlement->to_member_id] as $id) $rows[$id] ??= ['member_id' => $id, 'share_minor' => 0, 'covered_minor' => 0];
            $rows[$settlement->from_member_id]['covered_minor'] += $settlement->amount_minor;
            $rows[$settlement->to_member_id]['covered_minor'] -= $settlement->amount_minor;
        }
        ksort($rows);
        foreach ($rows as &$row) $row['remaining_minor'] = max(0, $row['share_minor'] - $row['covered_minor']);
        return array_values($rows);
    }

    /**
     * Undoes "Mark as paid" for a traveller's share: repayments recorded against this bill from that traveller go away.
     * One that came from an existing trip reimbursement goes back to being that reimbursement (the money was really
     * sent); one recorded as "a separate new payment" is removed. The organizer, or the traveller who received it.
     */
    public function markUnpaid(Expense $expense, int $memberId, User $actor, int $version): void
    {
        Gate::forUser($actor)->authorize('view', $expense->trip);
        DB::transaction(function () use ($expense, $memberId, $actor, $version) {
            $trip = Trip::whereKey($expense->trip_id)->lockForUpdate()->firstOrFail();
            $expense = Expense::whereKey($expense->id)->lockForUpdate()->firstOrFail();
            $fail = fn ($message) => throw ValidationException::withMessages(['share' => $message]);
            if ($expense->version !== $version) $fail('This expense changed. Refresh before marking a share unpaid.');
            $actorMemberId = (int) $trip->members()->where('user_id', $actor->id)->value('id');
            $organizer = $trip->organizer_user_id === $actor->id;
            $repayments = $expense->settlements()->where('from_member_id', $memberId)->where('status', 'confirmed')
                ->when(! $organizer, fn ($q) => $q->where('to_member_id', $actorMemberId))->lockForUpdate()->get();
            if ($repayments->isEmpty()) $fail($organizer ? 'No repayment is recorded for this share.' : 'You can only undo repayments made to you.');
            $before = $repayments->toArray();
            foreach ($repayments as $repayment) {
                $source = $repayment->applied_from_id && $repayment->applied_from_id !== $repayment->id ? Settlement::whereKey($repayment->applied_from_id)->whereNull('expense_id')->lockForUpdate()->first() : null;
                if ($repayment->applied_from_id === $repayment->id || (! $repayment->applied_from_id && $repayment->payment_method !== 'other')) {
                    // A whole trip reimbursement (or one from before this was tracked) was applied: detach it again.
                    $repayment->update(['expense_id' => null, 'applied_from_id' => null]);
                } elseif ($source) {
                    $source->increment('amount_minor', $repayment->amount_minor);
                    $repayment->delete();
                } else {
                    $repayment->delete();
                }
            }
            $expense->increment('version');
            ActivityLog::create(['trip_id' => $expense->trip_id, 'actor_user_id' => $actor->id, 'subject_type' => 'expense', 'subject_id' => $expense->id, 'action' => 'share_unpaid', 'before' => ['repayments' => $before], 'after' => ['member_id' => $memberId]]);
        });
    }

    public function markPaid(Expense $expense, int $memberId, User $actor, int $version, ?int $settlementId = null, bool $newPayment = false): void
    {
        Gate::forUser($actor)->authorize('view', $expense->trip);
        DB::transaction(function () use ($expense, $memberId, $actor, $version, $settlementId, $newPayment) {
            // Serialize application of a trip-wide reimbursement across different bills.
            $trip = Trip::whereKey($expense->trip_id)->lockForUpdate()->firstOrFail();
            $expense = Expense::whereKey($expense->id)->lockForUpdate()->firstOrFail();
            $actorMemberId = (int) $trip->members()->where('user_id', $actor->id)->value('id');
            $organizer = $trip->organizer_user_id === $actor->id;
            abort_unless($organizer || $expense->payments()->where('trip_member_id', $actorMemberId)->whereNotNull('paid_at')->exists(), 403);
            $fail = fn ($message) => throw ValidationException::withMessages(['share' => $message]);
            if ($expense->version !== $version) $fail('This expense changed. Refresh before marking a share paid.');
            if (! in_array($expense->status, [ExpenseStatus::Open, ExpenseStatus::Completed, ExpenseStatus::Closed], true)) $fail('Shares cannot be paid on a draft or cancelled expense.');
            if ((int) $expense->payments()->whereNotNull('paid_at')->sum('amount_minor') !== $expense->total_minor || (int) $expense->allocations()->sum('amount_minor') !== $expense->total_minor) $fail('Record the full merchant payment and allocate the total before recording reimbursements.');
            $rows = collect($this->rows($expense));
            $row = $rows->firstWhere('member_id', $memberId);
            if (! $row) $fail('This traveler has no assigned share.');
            $remaining = $row['remaining_minor'];
            if ($remaining === 0) return;
            $creditors = $rows->filter(fn ($creditor) => $creditor['covered_minor'] > $creditor['share_minor'] && ($organizer || $creditor['member_id'] === $actorMemberId));
            if ($creditors->isEmpty()) $fail('You can only confirm repayments for money owed to you.');
            $matching = $trip->settlements()->whereNull('expense_id')->where('from_member_id', $memberId)->whereIn('to_member_id', $creditors->pluck('member_id'))->whereIn('status', ['pending', 'confirmed']);
            if (! $settlementId && ! $newPayment && (clone $matching)->exists()) $fail('Choose the existing reimbursement to apply, or select a separate new payment.');
            $existing = $settlementId ? (clone $matching)->whereKey($settlementId)->lockForUpdate()->first() : null;
            if ($settlementId && ! $existing) $fail('That reimbursement is no longer available for this share. Refresh and select again.');
            $before = ['status' => $expense->status->value, 'source_settlement' => $existing?->toArray()];
            if ($expense->status === ExpenseStatus::Open) {
                $expense->update(['status' => ExpenseStatus::Completed, 'completed_at' => now()]);
            }
            $recorded = 0;
            foreach ($creditors as $creditor) {
                $amount = min($remaining, max(0, $creditor['covered_minor'] - $creditor['share_minor']));
                if ($existing) {
                    if ($existing->to_member_id !== $creditor['member_id']) continue;
                    $amount = min($amount, $existing->amount_minor);
                }
                if ($amount === 0) continue;
                $values = ['trip_id' => $expense->trip_id, 'expense_id' => $expense->id, 'from_member_id' => $memberId, 'to_member_id' => $creditor['member_id'], 'amount_minor' => $amount, 'currency_code' => $expense->currency_code, 'payment_method' => $existing?->payment_method ?? 'other', 'status' => 'confirmed', 'paid_at' => $existing?->paid_at ?? now(), 'confirmed_at' => $existing?->confirmed_at ?? now()];
                if ($existing && $existing->amount_minor === $amount) {
                    $existing->update($values + ['applied_from_id' => $existing->id]);
                } else {
                    if ($existing) {
                        // Preserve the unallocated remainder; total recorded money is unchanged.
                        $existing->decrement('amount_minor', $amount);
                    }
                    $expense->settlements()->create($values + ['applied_from_id' => $existing?->id]);
                }
                $remaining -= $amount;
                $recorded += $amount;
            }
            if ($recorded === 0) $fail('No outstanding repayment can be confirmed.');
            $expense->increment('version');
            ActivityLog::create(['trip_id' => $expense->trip_id, 'actor_user_id' => $actor->id, 'subject_type' => 'expense', 'subject_id' => $expense->id, 'action' => 'share_paid', 'before' => $before, 'after' => ['member_id' => $memberId, 'amount_minor' => $recorded, 'source_settlement_id' => $settlementId, 'remaining_minor' => $remaining, 'status' => $expense->status->value]]);
        });
    }
}
