<?php
namespace App\Http\Controllers;

use App\Models\{Settlement, Trip};
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Gate;
use Illuminate\Validation\Rule;

class SettlementController extends Controller
{
    public function store(Request $request, Trip $trip)
    {
        Gate::authorize('view', $trip);
        $member = $trip->members()->where('user_id', $request->user()->id)->firstOrFail();
        $data = $request->validate(['to_member_id' => ['required', Rule::in($trip->members()->where('status', 'active')->where('id', '!=', $member->id)->pluck('id')->all())], 'amount_minor' => 'required|integer|min:1|max:100000000', 'payment_method' => 'required|in:cash,bank_transfer,gcash,other']);
        $settlement = $trip->settlements()->create($data + ['from_member_id' => $member->id, 'currency_code' => $trip->currency_code, 'paid_at' => now()]);
        return $request->is('api/*') ? response()->json(['data' => $settlement->fresh()], 201) : back();
    }
    /**
     * "Mark all as paid" between you and one traveller: every bill between the two of you ends up with its share marked
     * paid on the expense itself, for both of you.
     *  1. Repayments already made without a bill ("Record money you sent") are attached to the open bills they cover.
     *  2. Bills that cancel out (they owe you for some, you owe them for others) are cleared against each other; no
     *     money moves, so these count at once.
     *  3. What is left (the net) is recorded per bill, oldest first. If they owe you, you are receiving it, so it counts
     *     at once; if you owe them, it waits for them to confirm (like "Record money you sent").
     */
    public function settleWith(Request $request, Trip $trip, int $member, \App\Services\TripBalances $balances)
    {
        Gate::authorize('view', $trip);
        $me = $trip->members()->where('user_id', $request->user()->id)->firstOrFail();
        abort_unless($member !== $me->id && $trip->members()->whereKey($member)->exists(), 404);
        $result = \Illuminate\Support\Facades\DB::transaction(function () use ($trip, $me, $member, $balances, $request) {
            Trip::whereKey($trip->id)->lockForUpdate()->first();
            $between = collect($balances->between($trip, $me->id))->firstWhere('member_id', $member);
            $lines = collect($between['lines'] ?? []);
            // What is still open on each bill: its line less repayments already tied to it (pending ones too, so nothing
            // waiting for confirmation is recorded twice). + they owe you, − you owe them.
            $repaid = $lines->where('type', 'payment')->whereNotNull('expense_id')->groupBy('expense_id')->map(fn ($g) => (int) $g->sum('amount_minor'));
            $open = $lines->where('type', 'expense')->mapWithKeys(fn ($l) => [$l['expense_id'] => (int) $l['amount_minor'] + ($repaid[$l['expense_id']] ?? 0)])->all();
            $theirs = array_filter($open, fn ($a) => $a > 0);                       // bills they owe you for
            $mine = array_map('abs', array_filter($open, fn ($a) => $a < 0));        // bills you owe them for
            $base = ['currency_code' => $trip->currency_code, 'payment_method' => 'other'];
            $touched = []; $created = 0;
            $record = function (int $expenseId, bool $fromThem, int $amount, bool $confirmed) use ($trip, $me, $member, $base, &$touched, &$created) {
                $trip->settlements()->create($base + ['expense_id' => $expenseId, 'from_member_id' => $fromThem ? $member : $me->id, 'to_member_id' => $fromThem ? $me->id : $member,
                    'amount_minor' => $amount, 'paid_at' => now(), 'status' => $confirmed ? 'confirmed' : 'pending', 'confirmed_at' => $confirmed ? now() : null]);
                $touched[$expenseId] = true; $created++;
            };
            // Spread an amount over bills (oldest first), calling $each(expenseId, piece); returns what didn't fit.
            $spread = function (array &$bills, int $amount, callable $each): int {
                foreach ($bills as $id => $left) {
                    if ($amount <= 0) { break; }
                    if ($left <= 0) { continue; }
                    $piece = min($left, $amount);
                    $each($id, $piece); $bills[$id] -= $piece; $amount -= $piece;
                }
                return $amount;
            };

            // 1. Confirmed repayments with no bill go onto the bills they pay for (same as "Mark paid" with an existing reimbursement).
            $general = $trip->settlements()->whereNull('expense_id')->where('status', 'confirmed')
                ->where(fn ($q) => $q->where(fn ($q) => $q->where('from_member_id', $member)->where('to_member_id', $me->id))->orWhere(fn ($q) => $q->where('from_member_id', $me->id)->where('to_member_id', $member)))
                ->orderBy('created_at')->lockForUpdate()->get();
            foreach ($general as $source) {
                $fromThem = $source->from_member_id === $member;
                if ($fromThem) { $bills = &$theirs; } else { $bills = &$mine; }
                $spread($bills, $source->amount_minor, function ($id, $piece) use ($source, &$touched, &$created) {
                    if ($piece === $source->amount_minor) {
                        $source->update(['expense_id' => $id, 'applied_from_id' => $source->id]);
                    } else {
                        $source->decrement('amount_minor', $piece);
                        Settlement::create(['trip_id' => $source->trip_id, 'expense_id' => $id, 'applied_from_id' => $source->id, 'from_member_id' => $source->from_member_id, 'to_member_id' => $source->to_member_id,
                            'amount_minor' => $piece, 'currency_code' => $source->currency_code, 'payment_method' => $source->payment_method, 'status' => 'confirmed', 'paid_at' => $source->paid_at, 'confirmed_at' => $source->confirmed_at]);
                    }
                    $touched[$id] = true; $created++;
                });
                unset($bills);
            }

            // 2. Bills that cancel out: both sides covered by the same amount, so the balance doesn't change.
            $offset = min(array_sum($theirs), array_sum($mine));
            if ($offset > 0) {
                $spread($theirs, $offset, fn ($id, $piece) => $record($id, true, $piece, true));
                $spread($mine, $offset, fn ($id, $piece) => $record($id, false, $piece, true));
            }

            // 3. The rest, capped at what is actually owed (counting repayments waiting for confirmation).
            $owed = (int) $lines->sum('amount_minor');
            $theyOwe = array_sum($theirs) > 0;
            $rest = $theyOwe ? min(array_sum($theirs), max(0, $owed)) : min(array_sum($mine), max(0, -$owed));
            if ($rest > 0) {
                if ($theyOwe) { $spread($theirs, $rest, fn ($id, $piece) => $record($id, true, $piece, true)); }
                else { $spread($mine, $rest, fn ($id, $piece) => $record($id, false, $piece, false)); }
            }
            if ($created === 0) {
                $waiting = $lines->where('pending', true)->isNotEmpty();
                abort(422, $waiting ? 'Repayments for this are already waiting for confirmation.' : 'There is nothing to settle between you two.');
            }
            // Expenses with a share just marked paid change version, so an open edit form refreshes first.
            \App\Models\Expense::whereKey(array_keys($touched))->increment('version');
            \App\Models\ActivityLog::create(['trip_id' => $trip->id, 'actor_user_id' => $request->user()->id, 'subject_type' => 'trip', 'subject_id' => $trip->id, 'action' => 'settled_with',
                'after' => ['member_id' => $member, 'bills' => count($touched), 'offset_minor' => $offset, 'paid_minor' => $rest, 'they_owed' => $theyOwe]]);
            return ['bills' => count($touched), 'offset_minor' => $offset, 'paid_minor' => $rest, 'confirmed' => $theyOwe || $rest === 0];
        });
        if (! $result['confirmed']) {
            $to = $trip->members()->find($member);
            app(\App\Services\Alerts::class)->notify($trip, $request->user(), 'expense', \App\Services\Alerts::firstName($request->user()).' paid you back',
                \App\Services\Alerts::money($result['paid_minor'], $trip->currency_code).'. Confirm it in Balances.', [], array_filter([$to?->user_id]));
        }
        return $request->is('api/*') ? response()->json(['data' => $result]) : back();
    }

    public function confirm(Request $request, Trip $trip, Settlement $settlement)
    {
        Gate::authorize('view', $trip);
        abort_unless($settlement->trip_id === $trip->id && $trip->members()->where('id', $settlement->to_member_id)->where('user_id', $request->user()->id)->exists(), 403);
        Settlement::whereKey($settlement->id)->where('status', 'pending')->update(['status' => 'confirmed', 'confirmed_at' => now()]);
        return $request->is('api/*') ? response()->json(['data' => $settlement->fresh()]) : back();
    }
}
