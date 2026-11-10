<?php
namespace App\Http\Controllers;

use App\Models\{ActivityLog, ExpenseCategory, Trip, TripInvitation, TripMember};
use App\Services\TripBalances;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\{DB, Gate};
use Illuminate\Support\Str;
use Inertia\Inertia;

class TripController extends Controller
{
    public function index(Request $request)
    {
        // Hidden (cancelled) trips are left out unless asked for with ?hidden=1.
        $mine = Trip::whereHas('members', fn ($q) => $q->where('user_id', $request->user()->id)->where('status', 'active'));
        $showHidden = $request->boolean('hidden');
        $hiddenCount = (clone $mine)->whereNotNull('hidden_at')->count();
        $trips = $mine->when($showHidden, fn ($q) => $q->whereNotNull('hidden_at'), fn ($q) => $q->whereNull('hidden_at'))->withCount('members')->latest()->get();
        return $request->is('api/*')
            ? response()->json(['data' => $trips, 'meta' => ['hidden_count' => $hiddenCount]])
            : Inertia::render('Trips/Index', ['trips' => $trips, 'hiddenCount' => $hiddenCount, 'showingHidden' => $showHidden,
                // For the plan-a-trip flow: destination suggestions (Google Places) and the optional AI planner.
                'mapsKey' => config('services.google_maps.browser_key'), 'aiPlanner' => app(\App\Services\Vertex\TripPlanner::class)->configured()]);
    }
    public function store(Request $request)
    {
        $data = $request->validate(['name' => 'required|string|max:120', 'destination' => 'required|string|max:200', 'description' => 'nullable|string|max:4000', 'starts_on' => 'required|date_format:Y-m-d', 'ends_on' => 'required|date_format:Y-m-d|after_or_equal:starts_on', 'timezone' => 'required|timezone', 'currency_code' => ['required', \Illuminate\Validation\Rule::in(Trip::CURRENCIES)], 'ai_prompt' => 'nullable|string|max:2000']);
        // An optional "plan it with AI" request from the create form opens the AI planner on the new trip.
        $aiPrompt = trim((string) ($data['ai_prompt'] ?? '')); unset($data['ai_prompt']);
        $trip = DB::transaction(function () use ($data, $request) {
            $trip = Trip::create([...$data, 'organizer_user_id' => $request->user()->id]);
            $trip->members()->create(['user_id' => $request->user()->id, 'status' => 'active', 'joined_at' => now()]);
            return $trip;
        });
        if ($request->is('api/*')) { return response()->json(['data' => $trip], 201); }
        return to_route('trips.show', $trip)->with('ai_prompt', $aiPrompt !== '' ? $aiPrompt : null);
    }
    public function show(Request $request, Trip $trip, TripBalances $balances)
    {
        Gate::authorize('view', $trip);
        $trip->load(['expenses.payments', 'expenses.allocations', 'expenses.settlements']);
        $shareRows = $trip->expenses->mapWithKeys(fn ($expense) => [$expense->id => app(\App\Services\ExpenseShares::class)->rows($expense)]);
        if ($request->is('api/*')) {
            return response()->json(['data' => [
                'trip' => $trip->load(['members.user:id,name,phone,other_contact,emergency_contact_name,emergency_contact_relationship,emergency_contact_phone,avatar_path', 'itineraryItems' => fn ($q) => $q->orderBy('starts_at')->orderBy('id'), 'expenses' => fn ($q) => $q->latest()->with(['category', 'items', 'allocations', 'payments', 'receipts:id,expense_id,processing_status']), 'settlements']),
                'categories' => ExpenseCategory::where('is_active', true)->where(fn ($q) => $q->whereNull('trip_id')->orWhere('trip_id', $trip->id))->orderBy('sort_order')->get(),
                'balances' => $balances->calculate($trip), 'dues' => $balances->dues($trip, $shareRows->all()), 'expense_shares' => (object) $shareRows->all(),
                'current_member_id' => $trip->members()->where('user_id', $request->user()->id)->value('id'),
                'is_organizer' => $trip->organizer_user_id === $request->user()->id,
                'ai_planner' => app(\App\Services\Vertex\TripPlanner::class)->configured(),
            ]]);
        }
        return Inertia::render('Trips/Show', [
            'trip' => $trip->load(['members.user:id,name,phone,other_contact,emergency_contact_name,emergency_contact_relationship,emergency_contact_phone,avatar_path', 'itineraryItems' => fn ($q) => $q->orderBy('starts_at')->orderBy('id'), 'expenses' => fn ($q) => $q->latest()->with(['category', 'items', 'allocations', 'payments', 'receipts:id,expense_id,processing_status']), 'settlements']),
            'categories' => ExpenseCategory::where('is_active', true)->where(fn ($q) => $q->whereNull('trip_id')->orWhere('trip_id', $trip->id))->orderBy('sort_order')->get(),
            'balances' => $balances->calculate($trip), 'dues' => $balances->dues($trip, $shareRows->all()), 'isOrganizer' => $trip->organizer_user_id === $request->user()->id,
            'expenseShares' => $shareRows,
            'googleMaps' => ['browserKey' => config('services.google_maps.browser_key'), 'mapId' => config('services.google_maps.map_id')],
            'currentMemberId' => $trip->members()->where('user_id', $request->user()->id)->value('id'), 'invitationUrl' => session('invitation_url'),
            'aiPrompt' => session('ai_prompt'), 'aiPlanner' => app(\App\Services\Vertex\TripPlanner::class)->configured(),
        ]);
    }
    /** Organizer edits the trip details. Currency is fixed once expenses exist, so amounts keep their meaning. */
    public function update(Request $request, Trip $trip)
    {
        Gate::authorize('plan', $trip);
        $data = $request->validate(['name' => 'required|string|max:120', 'destination' => 'required|string|max:200', 'description' => 'nullable|string|max:4000', 'starts_on' => 'required|date_format:Y-m-d', 'ends_on' => 'required|date_format:Y-m-d|after_or_equal:starts_on', 'timezone' => 'required|timezone', 'currency_code' => ['required', \Illuminate\Validation\Rule::in(Trip::CURRENCIES)]]);
        if ($data['currency_code'] !== $trip->currency_code && $trip->expenses()->exists()) {
            throw \Illuminate\Validation\ValidationException::withMessages(['currency_code' => 'The currency cannot change after expenses have been recorded.']);
        }
        $before = $trip->only(array_keys($data));
        $trip->update($data);
        ActivityLog::create(['trip_id' => $trip->id, 'actor_user_id' => $request->user()->id, 'subject_type' => 'trip', 'subject_id' => $trip->id, 'action' => 'updated', 'before' => $before, 'after' => $trip->only(array_keys($data))]);
        return $request->is('api/*') ? response()->json(['data' => $trip->fresh()]) : back();
    }

    /** Organizer cancels the trip, optionally hiding it from everyone's trip list. */
    public function cancel(Request $request, Trip $trip)
    {
        Gate::authorize('update', $trip);
        $data = $request->validate(['reason' => 'nullable|string|max:500', 'hide' => 'boolean']);
        abort_if($trip->isCancelled(), 422, 'This trip is already cancelled.');
        $hide = (bool) ($data['hide'] ?? false);
        $before = $trip->only(['status']);
        $trip->update(['status' => 'cancelled', 'cancelled_at' => now(), 'cancellation_reason' => $data['reason'] ?? null, 'hidden_at' => $hide ? now() : null]);
        ActivityLog::create(['trip_id' => $trip->id, 'actor_user_id' => $request->user()->id, 'subject_type' => 'trip', 'subject_id' => $trip->id, 'action' => 'cancelled', 'before' => $before, 'after' => ['status' => 'cancelled', 'hidden' => $hide, 'reason' => $data['reason'] ?? null]]);
        if ($request->is('api/*')) { return response()->json(['data' => $trip->fresh()]); }
        return $hide ? to_route('dashboard') : back();
    }

    /** Organizer undoes a cancellation; the trip becomes visible and editable again. */
    public function reopen(Request $request, Trip $trip)
    {
        Gate::authorize('update', $trip);
        abort_unless($trip->isCancelled(), 422, 'Only a cancelled trip can be reopened.');
        $trip->update(['status' => 'planning', 'cancelled_at' => null, 'cancellation_reason' => null, 'hidden_at' => null]);
        ActivityLog::create(['trip_id' => $trip->id, 'actor_user_id' => $request->user()->id, 'subject_type' => 'trip', 'subject_id' => $trip->id, 'action' => 'reopened', 'before' => ['status' => 'cancelled'], 'after' => ['status' => 'planning']]);
        return $request->is('api/*') ? response()->json(['data' => $trip->fresh()]) : back();
    }

    /** Organizer hides or shows a cancelled trip in members' trip lists. */
    public function visibility(Request $request, Trip $trip)
    {
        Gate::authorize('update', $trip);
        $hidden = $request->validate(['hidden' => 'required|boolean'])['hidden'];
        abort_if($hidden && ! $trip->isCancelled(), 422, 'Cancel the trip before hiding it.');
        $trip->update(['hidden_at' => $hidden ? now() : null]);
        return $request->is('api/*') ? response()->json(['data' => $trip->fresh()]) : ($hidden ? to_route('dashboard') : back());
    }

    public function invite(Request $request, Trip $trip)
    {
        Gate::authorize('plan', $trip);
        $token = Str::random(48);
        $invitation = TripInvitation::create(['trip_id' => $trip->id, 'created_by_user_id' => $request->user()->id, 'token_hash' => hash('sha256', $token), 'expires_at' => now()->addDays(7)]);
        if ($request->is('api/*')) {
            return response()->json(['data' => ['token' => $token, 'url' => route('invitations.show', $token), 'expires_at' => $invitation->expires_at]], 201)->header('Cache-Control', 'no-store');
        }
        return back()->with('invitation_url', route('invitations.show', $token));
    }
    public function invitation(Request $request, string $token)
    {
        $invitation = TripInvitation::with('trip.organizer')->where('token_hash', hash('sha256', $token))->first();
        $valid = $invitation && ! $invitation->revoked_at && $invitation->expires_at->isFuture() && $invitation->uses_count < $invitation->max_uses;
        if (! $valid) {
            if ($request->is('api/*')) { abort(410, 'This invitation has expired or is unavailable.'); }
            return Inertia::render('Trips/Join', ['invitation' => null, 'token' => null, 'memberTripId' => null])->toResponse($request)->setStatusCode(410)->header('Referrer-Policy', 'no-referrer')->header('Cache-Control', 'private, no-store');
        }
        if ($request->is('api/*')) {
            $trip = $invitation->trip;
            return response()->json(['data' => ['organizer_name' => $trip->organizer->name, 'trip_name' => $trip->name, 'destination' => $trip->destination, 'starts_on' => $trip->starts_on->format('Y-m-d'), 'ends_on' => $trip->ends_on->format('Y-m-d')]])->header('Cache-Control', 'no-store');
        }
        if (! $request->user()) {
            $request->session()->put('url.intended', route('invitations.show', $token));
        }
        $trip = $invitation->trip;
        $isMember = $request->user() && $trip->members()->where('user_id', $request->user()->id)->where('status', 'active')->exists();
        return Inertia::render('Trips/Join', [
            'invitation' => ['organizerName' => $trip->organizer->name, 'tripName' => $trip->name, 'destination' => $trip->destination, 'startsOn' => $trip->starts_on->format('F j, Y'), 'endsOn' => $trip->ends_on->format('F j, Y')],
            'token' => $token, 'memberTripId' => $isMember ? $trip->id : null,
        ])->toResponse($request)->header('Referrer-Policy', 'no-referrer')->header('Cache-Control', 'private, no-store');
    }
    public function join(Request $request, string $token)
    {
        $tripId = DB::transaction(function () use ($request, $token) {
            $invitation = TripInvitation::where('token_hash', hash('sha256', $token))->lockForUpdate()->firstOrFail();
            abort_if($invitation->revoked_at || $invitation->expires_at->isPast() || $invitation->uses_count >= $invitation->max_uses, 410);
            $member = TripMember::firstOrCreate(['trip_id' => $invitation->trip_id, 'user_id' => $request->user()->id], ['status' => 'active', 'joined_at' => now()]);
            abort_unless($member->status === 'active', 403, 'This membership is inactive. Contact the trip organizer.');
            if ($member->wasRecentlyCreated) { $invitation->increment('uses_count'); }
            return $invitation->trip_id;
        });
        return $request->is('api/*') ? response()->json(['data' => ['trip_id' => $tripId]]) : to_route('trips.show', $tripId);
    }
    public function itinerary(Request $request, Trip $trip)
    {
        Gate::authorize('plan', $trip);
        $data = $this->itineraryData($request, $trip);
        $item = $trip->itineraryItems()->create([...$data, 'timezone' => $trip->timezone, 'created_by_user_id' => $request->user()->id]);
        $this->itineraryAlert($request, $trip, 'added “'.$item->title.'”', $item);
        return $request->is('api/*') ? response()->json(['data' => $item], 201) : back();
    }

    public function updateItinerary(Request $request, Trip $trip, \App\Models\ItineraryItem $item)
    {
        Gate::authorize('plan', $trip);
        abort_unless($item->trip_id === $trip->id, 404);
        $item->update($this->itineraryData($request, $trip));
        $this->itineraryAlert($request, $trip, 'changed “'.$item->title.'”', $item);
        return $request->is('api/*') ? response()->json(['data' => $item->fresh()]) : back();
    }

    /** Lets everyone else on the trip know the plan changed. */
    private function itineraryAlert(Request $request, Trip $trip, string $what, \App\Models\ItineraryItem $item): void
    {
        $when = $item->starts_at?->setTimezone($trip->timezone)->format('D, M j · g:i A');
        app(\App\Services\Alerts::class)->notify($trip, $request->user(), 'itinerary', 'Itinerary updated',
            \App\Services\Alerts::firstName($request->user()).' '.$what.($when ? " ({$when})" : ''), ['item_id' => $item->id]);
    }

    /** Any traveller on the trip can tick an activity off (or undo it); everyone sees the same state. */
    public function itineraryDone(Request $request, Trip $trip, \App\Models\ItineraryItem $item)
    {
        Gate::authorize('view', $trip);
        abort_unless($item->trip_id === $trip->id, 404);
        $request->validate(['done' => 'required|boolean']);
        $done = $request->boolean('done');
        if ($done !== ($item->completed_at !== null)) {
            $item->update($done ? ['completed_at' => now(), 'completed_by_user_id' => $request->user()->id] : ['completed_at' => null, 'completed_by_user_id' => null]);
        }
        return $request->is('api/*') || $request->expectsJson() ? response()->json(['data' => $item->fresh()]) : back();
    }

    private function itineraryData(Request $request, Trip $trip): array
    {
        $data = $request->validate(['title' => 'required|string|max:200', 'location' => 'nullable|string|max:200', 'description' => 'nullable|string|max:4000', 'transport_mode' => 'nullable|in:car,van,taxi,motorcycle,tricycle,walking,bicycle,transit,ferry,flight,other', 'latitude' => 'nullable|numeric|between:-90,90|required_with:longitude', 'longitude' => 'nullable|numeric|between:-180,180|required_with:latitude', 'cost_min_minor' => 'nullable|integer|min:0|max:100000000|required_with:cost_max_minor', 'cost_max_minor' => 'nullable|integer|min:0|max:100000000|required_with:cost_min_minor|gte:cost_min_minor', 'cost_note' => 'nullable|string|max:255', 'starts_at' => 'required|date', 'ends_at' => 'required|date|after_or_equal:starts_at']);
        // Coordinates only describe a picked place; a missing pin or cleared location resets them.
        $hasPin = ! empty($data['location']) && isset($data['latitude'], $data['longitude']);
        $data['latitude'] = $hasPin ? $data['latitude'] : null;
        $data['longitude'] = $hasPin ? $data['longitude'] : null;
        foreach (['starts_at', 'ends_at'] as $field) { $data[$field] = \Carbon\CarbonImmutable::parse($data[$field], $trip->timezone)->utc(); }
        return $data;
    }
}
