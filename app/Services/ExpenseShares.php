<?php

namespace App\Services;

use App\Enums\ExpenseStatus;
use App\Models\{ActivityLog, Expense, User};
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

    public function markPaid(Expense $expense, int $memberId, User $actor, int $version): void
    {
        Gate::forUser($actor)->authorize('update', $expense->trip);
        DB::transaction(function () use ($expense, $memberId, $actor, $version) {
            $expense = Expense::whereKey($expense->id)->lockForUpdate()->firstOrFail();
            $fail = fn ($message) => throw ValidationException::withMessages(['share' => $message]);
            if ($expense->version !== $version) $fail('This expense changed. Refresh before marking a share paid.');
            if (! in_array($expense->status, [ExpenseStatus::Open, ExpenseStatus::Completed, ExpenseStatus::Closed], true)) $fail('Shares cannot be paid on a draft or cancelled expense.');
            if ((int) $expense->payments()->whereNotNull('paid_at')->sum('amount_minor') !== $expense->total_minor || (int) $expense->allocations()->sum('amount_minor') !== $expense->total_minor) $fail('Record the full merchant payment and allocate the total before recording reimbursements.');
            // Trip-wide reimbursements cannot reliably be attributed to one expense.
            if ($expense->trip->settlements()->whereNull('expense_id')->whereIn('status', ['pending', 'confirmed'])->exists()) $fail('This trip has general reimbursements. Reconcile those first; they cannot automatically be attributed to this expense.');
            $rows = collect($this->rows($expense));
            $row = $rows->firstWhere('member_id', $memberId);
            if (! $row) $fail('This traveler has no assigned share.');
            $remaining = $row['remaining_minor'];
            if ($remaining === 0) return;
            if ($expense->status === ExpenseStatus::Open) {
                app(ExpenseLifecycle::class)->transition($expense, $actor, ExpenseStatus::Completed, $version);
                $expense->refresh();
            }
            foreach ($rows as $creditor) {
                $amount = min($remaining, max(0, $creditor['covered_minor'] - $creditor['share_minor']));
                if ($amount === 0) continue;
                $expense->settlements()->create(['trip_id' => $expense->trip_id, 'from_member_id' => $memberId, 'to_member_id' => $creditor['member_id'], 'amount_minor' => $amount, 'currency_code' => $expense->currency_code, 'payment_method' => 'other', 'status' => 'confirmed', 'paid_at' => now(), 'confirmed_at' => now()]);
                $remaining -= $amount;
            }
            if ($remaining !== 0) $fail('The recorded payments do not cover this reimbursement.');
            $expense->increment('version');
            ActivityLog::create(['trip_id' => $expense->trip_id, 'actor_user_id' => $actor->id, 'subject_type' => 'expense', 'subject_id' => $expense->id, 'action' => 'share_paid', 'after' => ['member_id' => $memberId, 'amount_minor' => $row['remaining_minor']]]);
        });
    }
}
