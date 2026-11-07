<?php

namespace App\Services;

use App\Enums\ExpenseStatus;
use App\Models\{ActivityLog, Expense, User};
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Gate;
use Illuminate\Validation\ValidationException;

class ExpenseLifecycle
{
    public function transition(Expense $expense, User $actor, ExpenseStatus $target, int $version, ?string $reason = null): Expense
    {
        Gate::forUser($actor)->authorize('update', $expense->trip);
        return DB::transaction(function () use ($expense, $actor, $target, $version, $reason) {
            $expense = Expense::whereKey($expense->id)->lockForUpdate()->firstOrFail();
            $fail = fn ($message) => throw ValidationException::withMessages(['status' => $message]);
            if ($expense->version !== $version) { $fail('This expense changed. Refresh before trying again.'); }
            $allowed = match ($expense->status) {
                ExpenseStatus::Draft => [ExpenseStatus::Open, ExpenseStatus::Cancelled],
                ExpenseStatus::Open => [ExpenseStatus::Completed, ExpenseStatus::Cancelled],
                ExpenseStatus::Completed => [ExpenseStatus::Closed],
                default => [],
            };
            if (! in_array($target, $allowed, true)) { $fail('That status change is not allowed. Paid expenses cannot be cancelled without a refund workflow.'); }
            if ($target === ExpenseStatus::Completed) {
                if ((int) $expense->payments()->whereNotNull('paid_at')->sum('amount_minor') !== $expense->total_minor ||
                    (int) $expense->allocations()->sum('amount_minor') !== $expense->total_minor) {
                    $fail('Payments and assigned shares must both equal the expense total.');
                }
            }
            if ($target === ExpenseStatus::Cancelled) {
                if (! trim($reason ?? '')) { $fail('Enter a cancellation reason.'); }
                if ($expense->payments()->whereNotNull('paid_at')->exists()) { $fail('This expense has recorded payments. Record a refund before cancelling.'); }
            }
            $before = ['status' => $expense->status->value, 'version' => $expense->version];
            $values = ['status' => $target, 'version' => $expense->version + 1];
            if ($target === ExpenseStatus::Completed) { $values['completed_at'] = now(); }
            if ($target === ExpenseStatus::Closed) { $values['closed_at'] = now(); }
            if ($target === ExpenseStatus::Cancelled) { $values['cancelled_at'] = now(); $values['cancellation_reason'] = trim($reason); }
            $expense->update($values);
            ActivityLog::create(['trip_id' => $expense->trip_id, 'actor_user_id' => $actor->id, 'subject_type' => 'expense', 'subject_id' => $expense->id, 'action' => 'status_changed', 'before' => $before, 'after' => ['status' => $target->value, 'reason' => $reason, 'version' => $expense->version]]);
            return $expense;
        });
    }
}
