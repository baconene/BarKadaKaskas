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
     * "Mark all as paid" between you and one traveller: settles what the two of you owe each other (as shown under
     * "Between you and each traveller"), bill by bill, oldest first, up to the net amount, so each bill's share shows
     * as paid. If they owe you, you are the one receiving the money, so the repayments are confirmed at once; if you
     * owe them, they are recorded as sent and wait for them to confirm (like "Record money you sent").
     */
    public function settleWith(Request $request, Trip $trip, int $member, \App\Services\TripBalances $balances)
    {
        Gate::authorize('view', $trip);
        $me = $trip->members()->where('user_id', $request->user()->id)->firstOrFail();
        abort_unless($member !== $me->id && $trip->members()->whereKey($member)->exists(), 404);
        $between = collect($balances->between($trip, $me->id))->firstWhere('member_id', $member);
        // Repayments still waiting for confirmation count here, so they are not recorded twice.
        $net = (int) collect($between['lines'] ?? [])->sum('amount_minor');
        abort_if($net === 0 || $net * (int) ($between['net_minor'] ?? 0) < 0, 422, ($between['net_minor'] ?? 0) ? 'Repayments for this are already waiting for confirmation.' : 'There is nothing to settle between you two.');
        $theyOwe = $net > 0;
        // Bills where money is owed in the net's direction, oldest first.
        // What is still open on each bill: its line less repayments already recorded for that bill (pending ones too, so
        // a repayment waiting for confirmation is not sent twice).
        $repaid = collect($between['lines'])->where('type', 'payment')->whereNotNull('expense_id')->groupBy('expense_id')->map(fn ($g) => (int) $g->sum('amount_minor'));
        $lines = collect($between['lines'])->where('type', 'expense')
            ->map(fn ($l) => ['amount_minor' => (int) $l['amount_minor'] + ($repaid[$l['expense_id']] ?? 0)] + $l)
            ->filter(fn ($l) => $theyOwe ? $l['amount_minor'] > 0 : $l['amount_minor'] < 0)->values();
        $left = abs($net); $created = 0;
        \Illuminate\Support\Facades\DB::transaction(function () use ($trip, $me, $member, $theyOwe, $lines, &$left, &$created) {
            foreach ($lines as $line) {
                if ($left <= 0) { break; }
                $amount = min($left, abs((int) $line['amount_minor']));
                $trip->settlements()->create([
                    'expense_id' => $line['expense_id'], 'from_member_id' => $theyOwe ? $member : $me->id, 'to_member_id' => $theyOwe ? $me->id : $member,
                    'amount_minor' => $amount, 'currency_code' => $trip->currency_code, 'payment_method' => 'other', 'paid_at' => now(),
                    'status' => $theyOwe ? 'confirmed' : 'pending', 'confirmed_at' => $theyOwe ? now() : null,
                ]);
                $left -= $amount; $created++;
            }
            // Anything not tied to a bill (e.g. after earlier general repayments) goes as one general repayment.
            if ($left > 0) {
                $trip->settlements()->create(['from_member_id' => $theyOwe ? $member : $me->id, 'to_member_id' => $theyOwe ? $me->id : $member, 'amount_minor' => $left,
                    'currency_code' => $trip->currency_code, 'payment_method' => 'other', 'paid_at' => now(), 'status' => $theyOwe ? 'confirmed' : 'pending', 'confirmed_at' => $theyOwe ? now() : null]);
                $created++;
            }
            \App\Models\ActivityLog::create(['trip_id' => $trip->id, 'actor_user_id' => request()->user()->id, 'subject_type' => 'trip', 'subject_id' => $trip->id, 'action' => 'settled_with', 'after' => ['member_id' => $member, 'they_owed' => $theyOwe, 'repayments' => $created]]);
        });
        if (! $theyOwe) {
            $to = $trip->members()->with('user')->find($member);
            app(\App\Services\Alerts::class)->notify($trip, $request->user(), 'expense', \App\Services\Alerts::firstName($request->user()).' paid you back',
                \App\Services\Alerts::money(abs($net), $trip->currency_code).' for '.$lines->count().' '.($lines->count() === 1 ? 'bill' : 'bills').'. Confirm it in Balances.', [], array_filter([$to?->user_id]));
        }
        return $request->is('api/*') ? response()->json(['data' => ['settled_minor' => abs($net), 'repayments' => $created, 'confirmed' => $theyOwe]]) : back();
    }

    public function confirm(Request $request, Trip $trip, Settlement $settlement)
    {
        Gate::authorize('view', $trip);
        abort_unless($settlement->trip_id === $trip->id && $trip->members()->where('id', $settlement->to_member_id)->where('user_id', $request->user()->id)->exists(), 403);
        Settlement::whereKey($settlement->id)->where('status', 'pending')->update(['status' => 'confirmed', 'confirmed_at' => now()]);
        return $request->is('api/*') ? response()->json(['data' => $settlement->fresh()]) : back();
    }
}
