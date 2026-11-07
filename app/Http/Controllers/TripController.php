<?php
namespace App\Http\Controllers;

use App\Models\{ExpenseCategory, Trip, TripInvitation, TripMember};
use App\Services\TripBalances;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\{DB, Gate};
use Illuminate\Support\Str;
use Inertia\Inertia;

class TripController extends Controller
{
    public function index(Request $request)
    {
        return Inertia::render('Trips/Index', ['trips' => Trip::whereHas('members', fn ($q) => $q->where('user_id', $request->user()->id)->where('status', 'active'))->withCount('members')->latest()->get()]);
    }
    public function store(Request $request)
    {
        $data = $request->validate(['name' => 'required|string|max:120', 'destination' => 'required|string|max:200', 'description' => 'nullable|string|max:4000', 'starts_on' => 'required|date_format:Y-m-d', 'ends_on' => 'required|date_format:Y-m-d|after_or_equal:starts_on', 'timezone' => 'required|timezone', 'currency_code' => 'required|in:PHP,USD,EUR,GBP,SGD,AUD,CAD']);
        $trip = DB::transaction(function () use ($data, $request) {
            $trip = Trip::create([...$data, 'organizer_user_id' => $request->user()->id]);
            $trip->members()->create(['user_id' => $request->user()->id, 'status' => 'active', 'joined_at' => now()]);
            return $trip;
        });
        return to_route('trips.show', $trip);
    }
    public function show(Request $request, Trip $trip, TripBalances $balances)
    {
        Gate::authorize('view', $trip);
        return Inertia::render('Trips/Show', [
            'trip' => $trip->load(['members.user:id,name', 'itineraryItems' => fn ($q) => $q->orderBy('starts_at'), 'expenses' => fn ($q) => $q->latest()->with(['category', 'items', 'allocations', 'payments', 'receipts:id,expense_id,processing_status']), 'settlements']),
            'categories' => ExpenseCategory::where('is_active', true)->where(fn ($q) => $q->whereNull('trip_id')->orWhere('trip_id', $trip->id))->orderBy('sort_order')->get(),
            'balances' => $balances->calculate($trip), 'isOrganizer' => $trip->organizer_user_id === $request->user()->id,
            'currentMemberId' => $trip->members()->where('user_id', $request->user()->id)->value('id'), 'invitationUrl' => session('invitation_url'),
        ]);
    }
    public function invite(Request $request, Trip $trip)
    {
        Gate::authorize('update', $trip);
        $token = Str::random(48);
        TripInvitation::create(['trip_id' => $trip->id, 'created_by_user_id' => $request->user()->id, 'token_hash' => hash('sha256', $token), 'expires_at' => now()->addDays(7)]);
        return back()->with('invitation_url', route('invitations.show', $token));
    }
    public function invitation(string $token)
    {
        $invitation = TripInvitation::where('token_hash', hash('sha256', $token))->whereNull('revoked_at')->where('expires_at', '>', now())->firstOrFail();
        abort_if($invitation->uses_count >= $invitation->max_uses, 410);
        return Inertia::render('Trips/Join', ['tripName' => $invitation->trip->name, 'token' => $token]);
    }
    public function join(Request $request, string $token)
    {
        $tripId = DB::transaction(function () use ($request, $token) {
            $invitation = TripInvitation::where('token_hash', hash('sha256', $token))->lockForUpdate()->firstOrFail();
            abort_if($invitation->revoked_at || $invitation->expires_at->isPast() || $invitation->uses_count >= $invitation->max_uses, 410);
            $member = TripMember::firstOrCreate(['trip_id' => $invitation->trip_id, 'user_id' => $request->user()->id], ['status' => 'active', 'joined_at' => now()]);
            if ($member->wasRecentlyCreated) { $invitation->increment('uses_count'); }
            return $invitation->trip_id;
        });
        return to_route('trips.show', $tripId);
    }
    public function itinerary(Request $request, Trip $trip)
    {
        Gate::authorize('update', $trip);
        $data = $request->validate(['title' => 'required|string|max:200', 'location' => 'nullable|string|max:200', 'description' => 'nullable|string|max:4000', 'starts_at' => 'required|date', 'ends_at' => 'required|date|after_or_equal:starts_at']);
        foreach (['starts_at', 'ends_at'] as $field) { $data[$field] = \Carbon\CarbonImmutable::parse($data[$field], $trip->timezone)->utc(); }
        $trip->itineraryItems()->create([...$data, 'timezone' => $trip->timezone, 'created_by_user_id' => $request->user()->id]);
        return back();
    }
}
