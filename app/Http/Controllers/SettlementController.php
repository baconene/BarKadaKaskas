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
        $data = $request->validate(['to_member_id' => ['required', Rule::in($trip->members()->where('id', '!=', $member->id)->pluck('id')->all())], 'amount_minor' => 'required|integer|min:1|max:100000000', 'payment_method' => 'required|in:cash,bank_transfer,gcash,other']);
        $trip->settlements()->create($data + ['from_member_id' => $member->id, 'currency_code' => $trip->currency_code, 'paid_at' => now()]);
        return back();
    }
    public function confirm(Request $request, Trip $trip, Settlement $settlement)
    {
        Gate::authorize('view', $trip);
        abort_unless($settlement->trip_id === $trip->id && $trip->members()->where('id', $settlement->to_member_id)->where('user_id', $request->user()->id)->exists(), 403);
        Settlement::whereKey($settlement->id)->where('status', 'pending')->update(['status' => 'confirmed', 'confirmed_at' => now()]);
        return back();
    }
}
