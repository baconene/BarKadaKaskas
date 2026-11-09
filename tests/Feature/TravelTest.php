<?php
namespace Tests\Feature;

use App\Enums\ExpenseStatus;
use App\Models\{Expense, ExpenseCategory, Settlement, Trip, User};
use App\Models\TripInvitation;
use Inertia\Testing\AssertableInertia as Assert;
use App\Services\TripBalances;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Http\UploadedFile;
use Illuminate\Support\Facades\Storage;
use Tests\TestCase;

class TravelTest extends TestCase
{
    use RefreshDatabase;
    private User $owner;
    private User $friend;
    private Trip $trip;
    private int $ownerMember;
    private int $friendMember;
    private int $category;

    protected function setUp(): void
    {
        parent::setUp(); $this->withoutVite();
        $this->owner = User::factory()->create(); $this->friend = User::factory()->create();
        $this->trip = Trip::create(['organizer_user_id' => $this->owner->id, 'name' => 'Palawan', 'destination' => 'El Nido', 'starts_on' => '2026-12-01', 'ends_on' => '2026-12-04']);
        $this->ownerMember = $this->trip->members()->create(['user_id' => $this->owner->id])->id;
        $this->friendMember = $this->trip->members()->create(['user_id' => $this->friend->id])->id;
        $this->category = ExpenseCategory::create(['name' => 'Food'])->id;
    }
    private function payload(bool $paid = true): array
    {
        return ['title' => 'Dinner', 'category_id' => $this->category, 'incurred_at' => '2026-12-01', 'total_minor' => 10001, 'split_method' => 'equal', 'payments' => $paid ? [['trip_member_id' => $this->ownerMember, 'amount_minor' => 10001]] : [], 'allocations' => [['trip_member_id' => $this->ownerMember, 'weight' => 1], ['trip_member_id' => $this->friendMember, 'weight' => 1]], 'items' => []];
    }
    private function createExpense(bool $paid = true): Expense
    {
        $this->actingAs($this->owner)->post("/trips/{$this->trip->id}/expenses", $this->payload($paid))->assertSessionHasNoErrors();
        return Expense::latest('id')->firstOrFail();
    }
    private function changeStatus(Expense $expense, string $status, array $extra = [])
    {
        return $this->patch("/trips/{$this->trip->id}/expenses/{$expense->id}/status", $extra + ['status' => $status, 'version' => $expense->fresh()->version]);
    }
    public function test_paid_expense_can_complete_and_close_without_erasing_debts(): void
    {
        $expense = $this->createExpense();
        $this->changeStatus($expense, 'completed')->assertSessionHasNoErrors();
        $this->assertSame(ExpenseStatus::Completed, $expense->fresh()->status);
        $before = (new TripBalances)->calculate($this->trip);
        $this->assertSame(5000, $before[0]['net_minor']); $this->assertSame(-5000, $before[1]['net_minor']);
        $this->changeStatus($expense, 'closed')->assertSessionHasNoErrors();
        $this->assertSame($before, (new TripBalances)->calculate($this->trip));
        $this->changeStatus($expense, 'open')->assertSessionHasErrors('status');
        $this->assertDatabaseCount('activity_logs', 3);
    }
    public function test_open_expense_paid_in_full_shows_what_each_traveller_owes(): void
    {
        $expense = $this->createExpense();
        $this->assertSame(ExpenseStatus::Open, $expense->fresh()->status);
        $this->assertSame([5000, -5000], array_column((new TripBalances)->calculate($this->trip), 'net_minor'));
        $this->actingAs($this->friend)->get("/trips/{$this->trip->id}")->assertInertia(fn (Assert $page) => $page->where('balances.1.net_minor', -5000));
        // Completing the bill later does not change anyone's balance.
        $this->actingAs($this->owner); $this->changeStatus($expense, 'completed')->assertSessionHasNoErrors();
        $this->assertSame([5000, -5000], array_column((new TripBalances)->calculate($this->trip), 'net_minor'));
    }
    public function test_open_expense_not_yet_paid_is_forecast_in_full(): void
    {
        $expense = $this->createExpense(false);
        // Nobody has paid the merchant yet: both travellers still owe their whole share.
        $this->assertSame([-5001, -5000], array_column((new TripBalances)->calculate($this->trip), 'net_minor'));
        $this->assertSame(10001, (new TripBalances)->unpaidToMerchants($this->trip));
        // A down payment is credited to whoever made it; the rest stays owed to the merchant.
        $this->post("/trips/{$this->trip->id}/expenses/{$expense->id}/payments", ['trip_member_id' => $this->ownerMember, 'amount_minor' => 3000, 'version' => 1])->assertSessionHasNoErrors();
        $this->assertSame([-2001, -5000], array_column((new TripBalances)->calculate($this->trip), 'net_minor'));
        $this->assertSame(7001, (new TripBalances)->unpaidToMerchants($this->trip));
    }
    public function test_unpaid_expense_can_cancel_and_is_excluded_from_balances(): void
    {
        $expense = $this->createExpense(false);
        $this->changeStatus($expense, 'completed')->assertSessionHasErrors('status');
        $this->changeStatus($expense, 'cancelled')->assertSessionHasErrors('status');
        $this->changeStatus($expense, 'cancelled', ['reason' => 'Booking cancelled'])->assertSessionHasNoErrors();
        $this->assertSame(ExpenseStatus::Cancelled, $expense->fresh()->status);
        $this->assertSame([0, 0], array_column((new TripBalances)->calculate($this->trip), 'net_minor'));
    }
    public function test_recorded_payment_cannot_be_hidden_by_cancellation(): void
    {
        $expense = $this->createExpense();
        $this->changeStatus($expense, 'cancelled', ['reason' => 'Changed plans'])->assertSessionHasErrors('status');
    }
    public function test_non_organizer_cannot_finalize_and_outsider_cannot_view(): void
    {
        $expense = $this->createExpense();
        $this->actingAs($this->friend); $this->changeStatus($expense, 'completed')->assertForbidden();
        $this->actingAs(User::factory()->create())->get("/trips/{$this->trip->id}")->assertForbidden();
    }
    public function test_cross_trip_allocations_are_rejected_atomically(): void
    {
        $other = Trip::create(['organizer_user_id' => $this->owner->id, 'name' => 'Other', 'destination' => 'Cebu', 'starts_on' => '2026-12-01', 'ends_on' => '2026-12-02']);
        $otherMember = $other->members()->create(['user_id' => $this->owner->id]);
        $data = $this->payload(); $data['allocations'][0]['trip_member_id'] = $otherMember->id;
        $this->actingAs($this->owner)->post("/trips/{$this->trip->id}/expenses", $data)->assertSessionHasErrors('allocations.0.trip_member_id');
        $this->assertDatabaseCount('expenses', 0);
    }
    public function test_stale_status_change_is_rejected(): void
    {
        $expense = $this->createExpense(); $this->changeStatus($expense, 'completed')->assertSessionHasNoErrors();
        $this->changeStatus($expense, 'closed', ['version' => 1])->assertSessionHasErrors('status');
    }
    public function test_itemized_splits_and_payment_completion(): void
    {
        $data = $this->payload(false); $data['split_method'] = 'itemized'; $data['allocations'] = [];
        $data['items'] = [['description' => 'Shared pizza', 'amount_minor' => 6000, 'member_ids' => [$this->ownerMember, $this->friendMember]], ['description' => 'Pasta', 'amount_minor' => 4001, 'member_ids' => [$this->friendMember]]];
        $this->actingAs($this->owner)->post("/trips/{$this->trip->id}/expenses", $data)->assertSessionHasNoErrors();
        $expense = Expense::firstOrFail();
        $this->assertSame(7001, (int) $expense->allocations()->where('trip_member_id', $this->friendMember)->sum('amount_minor'));
        $this->post("/trips/{$this->trip->id}/expenses/{$expense->id}/payments", ['trip_member_id' => $this->ownerMember, 'amount_minor' => 10001, 'version' => 1])->assertSessionHasNoErrors();
        $this->changeStatus($expense, 'completed')->assertSessionHasNoErrors();
    }
    public function test_only_recipient_confirms_settlement_and_confirmation_is_idempotent(): void
    {
        $expense = $this->createExpense(); $this->changeStatus($expense, 'completed');
        $this->actingAs($this->friend)->post("/trips/{$this->trip->id}/settlements", ['to_member_id' => $this->ownerMember, 'amount_minor' => 5000, 'payment_method' => 'gcash'])->assertSessionHasNoErrors();
        $settlement = Settlement::firstOrFail(); $url = "/trips/{$this->trip->id}/settlements/{$settlement->id}/confirm";
        $this->post($url)->assertForbidden();
        $this->actingAs($this->owner)->post($url)->assertRedirect(); $this->post($url)->assertRedirect();
        $this->assertSame([0, 0], array_column((new TripBalances)->calculate($this->trip), 'net_minor'));
    }
    public function test_receipts_are_private(): void
    {
        Storage::fake('local'); $expense = $this->createExpense();
        $this->post("/trips/{$this->trip->id}/expenses/{$expense->id}/receipts", ['receipt' => UploadedFile::fake()->create('receipt.pdf', 10, 'application/pdf')])->assertSessionHasNoErrors();
        $receipt = $expense->receipts()->firstOrFail();
        $this->actingAs($this->friend)->get("/receipts/{$receipt->id}")->assertOk();
        $this->actingAs(User::factory()->create())->get("/receipts/{$receipt->id}")->assertForbidden();
    }
    public function test_invitation_join_is_idempotent_and_multiple_trips_are_supported(): void
    {
        $response = $this->actingAs($this->owner)->post("/trips/{$this->trip->id}/invitations");
        $url = $response->getSession()->get('invitation_url');
        $newUser = User::factory()->create();
        $this->actingAs($newUser)->post($url)->assertRedirect(); $this->post($url)->assertRedirect();
        $this->assertSame(3, $this->trip->members()->count());
        $this->post('/trips', ['name' => 'Baguio', 'destination' => 'Baguio', 'starts_on' => '2027-01-01', 'ends_on' => '2027-01-03', 'currency_code' => 'PHP', 'timezone' => 'Asia/Manila'])->assertSessionHasNoErrors();
        $this->assertDatabaseCount('trips', 2);
    }

    private function publicInvitation(string $token = 'test-invitation'): TripInvitation
    {
        return TripInvitation::create(['trip_id' => $this->trip->id, 'created_by_user_id' => $this->owner->id, 'token_hash' => hash('sha256', $token), 'expires_at' => now()->addDays(7)]);
    }

    public function test_guest_can_preview_invitation_without_private_trip_data_or_joining(): void
    {
        $this->publicInvitation();
        $this->get('/join/test-invitation')->assertOk()
            ->assertHeader('Referrer-Policy', 'no-referrer')
            ->assertSessionHas('url.intended', route('invitations.show', 'test-invitation'))
            ->assertInertia(fn (Assert $page) => $page->component('Trips/Join')
                ->where('invitation.organizerName', $this->owner->name)
                ->where('invitation.tripName', 'Palawan')->where('invitation.startsOn', 'December 1, 2026')
                ->where('invitation.destination', 'El Nido')->where('memberTripId', null)
                ->missing('trip')->missing('expenses')->missing('members'));
        $this->post('/join/test-invitation')->assertRedirect('/login');
        $this->assertSame(2, $this->trip->members()->count());
    }

    public function test_login_returns_guest_to_invitation_for_explicit_join(): void
    {
        $this->publicInvitation();
        $this->get('/join/test-invitation');
        $this->post('/login', ['email' => $this->friend->email, 'password' => 'password'])
            ->assertRedirect(route('invitations.show', 'test-invitation'));
        $this->assertSame(2, $this->trip->members()->count());
    }

    public function test_signup_returns_guest_to_invitation_without_automatically_joining(): void
    {
        $this->publicInvitation();
        $this->get('/join/test-invitation');
        $this->post('/register', ['name' => 'New Traveler', 'email' => 'traveler@example.com', 'password' => 'password', 'password_confirmation' => 'password'])
            ->assertRedirect(route('invitations.show', 'test-invitation'));
        $this->assertSame(2, $this->trip->members()->count());
    }

    public function test_invalid_expired_revoked_and_exhausted_invitations_show_no_trip_details(): void
    {
        $invitation = $this->publicInvitation();
        foreach ([['expires_at' => now()->subDay()], ['expires_at' => now()->addDay(), 'revoked_at' => now()], ['revoked_at' => null, 'uses_count' => 50]] as $values) {
            $invitation->update($values);
            $this->get('/join/test-invitation')->assertStatus(410)->assertInertia(fn (Assert $page) => $page->component('Trips/Join')->where('invitation', null)->where('token', null));
        }
        $this->get('/join/not-real')->assertStatus(410);
    }

    public function test_existing_member_sees_view_trip_action(): void
    {
        $this->publicInvitation();
        $this->actingAs($this->friend)->get('/join/test-invitation')->assertOk()
            ->assertInertia(fn (Assert $page) => $page->where('memberTripId', $this->trip->id));
    }

    public function test_organizer_can_reassign_completed_expense_and_balances_recalculate(): void
    {
        $expense = $this->createExpense(); $this->changeStatus($expense, 'completed');
        $paymentId = $expense->payments()->first()->id;
        $data = $this->payload(); $data['version'] = $expense->fresh()->version;
        $data['split_method'] = 'percentage';
        $data['allocations'][0]['weight'] = 2500; $data['allocations'][1]['weight'] = 7500;
        $this->patch("/trips/{$this->trip->id}/expenses/{$expense->id}", $data)->assertSessionHasNoErrors();
        $this->assertSame([7501, -7501], array_column((new TripBalances)->calculate($this->trip), 'net_minor'));
        $this->assertSame($paymentId, $expense->payments()->first()->id);
        $this->assertSame(ExpenseStatus::Completed, $expense->fresh()->status);
        $this->assertDatabaseHas('activity_logs', ['subject_id' => $expense->id, 'action' => 'updated']);
    }

    public function test_edit_can_switch_to_itemized_and_add_members_to_receipt_lines(): void
    {
        $expense = $this->createExpense(); $data = $this->payload();
        $data['version'] = 1; $data['split_method'] = 'itemized';
        $data['items'] = [['description' => 'Shared food', 'amount_minor' => 10001, 'member_ids' => [$this->ownerMember, $this->friendMember]]];
        $url = "/trips/{$this->trip->id}/expenses/{$expense->id}";
        $this->patch($url, $data)->assertSessionHasNoErrors();
        $this->assertSame(2, $expense->allocations()->count());
        $data['version'] = 2; $data['items'][0]['member_ids'] = [$this->friendMember];
        $this->patch($url, $data)->assertSessionHasNoErrors();
        $this->assertSame(1, $expense->items()->count());
        $this->assertSame(1, $expense->allocations()->count());
        $this->assertSame(10001, $expense->allocations()->first()->amount_minor);
    }

    public function test_edits_reject_members_stale_versions_and_locked_expenses(): void
    {
        $expense = $this->createExpense(); $data = $this->payload() + ['version' => 1];
        $url = "/trips/{$this->trip->id}/expenses/{$expense->id}";
        $this->actingAs($this->friend)->patch($url, $data)->assertForbidden();
        $this->actingAs($this->owner)->patch($url, $data)->assertSessionHasNoErrors();
        $this->patch($url, $data)->assertSessionHasErrors('expense');
        $this->changeStatus($expense, 'completed'); $this->changeStatus($expense, 'closed');
        $data['version'] = $expense->fresh()->version;
        $this->patch($url, $data)->assertSessionHasErrors('expense');
        $this->assertSame(ExpenseStatus::Closed, $expense->fresh()->status);
    }

    public function test_invalid_edit_rolls_back_and_cannot_remove_recorded_payments(): void
    {
        $expense = $this->createExpense(); $data = $this->payload() + ['version' => 1];
        $url = "/trips/{$this->trip->id}/expenses/{$expense->id}";
        $data['payments'] = [];
        $this->patch($url, $data)->assertSessionHasErrors('expense');
        $this->assertSame(1, $expense->payments()->count());
        $data = $this->payload() + ['version' => 1]; $data['split_method'] = 'exact';
        $this->patch($url, $data)->assertSessionHasErrors('allocations');
        $this->assertSame(1, $expense->fresh()->version);
        $this->assertSame(10001, (int) $expense->allocations()->sum('amount_minor'));
    }

    public function test_mark_share_paid_records_reimbursement_not_a_second_merchant_payment(): void
    {
        $expense = $this->createExpense();
        $url = "/trips/{$this->trip->id}/expenses/{$expense->id}/shares/paid";
        $this->post($url, ['member_id' => $this->friendMember, 'version' => 1])->assertSessionHasNoErrors();
        $this->assertSame(ExpenseStatus::Completed, $expense->fresh()->status);
        $this->assertSame(10001, (int) $expense->payments()->sum('amount_minor'));
        $this->assertDatabaseHas('settlements', ['expense_id' => $expense->id, 'from_member_id' => $this->friendMember, 'to_member_id' => $this->ownerMember, 'amount_minor' => 5000, 'status' => 'confirmed']);
        $this->assertSame([0, 0], array_column((new TripBalances)->calculate($this->trip), 'net_minor'));
        $this->post($url, ['member_id' => $this->friendMember, 'version' => $expense->fresh()->version])->assertSessionHasNoErrors();
        $this->assertDatabaseCount('settlements', 1);
        $rows = app(\App\Services\ExpenseShares::class)->rows($expense->fresh());
        $this->assertSame([0, 0], array_column($rows, 'remaining_minor'));
    }

    public function test_paid_expense_cancellation_requires_explicit_void_and_preserves_history(): void
    {
        $expense = $this->createExpense();
        $this->post("/trips/{$this->trip->id}/expenses/{$expense->id}/shares/paid", ['member_id' => $this->friendMember, 'version' => 1])->assertSessionHasNoErrors();
        $this->changeStatus($expense, 'cancelled', ['reason' => 'Duplicate bill'])->assertSessionHasErrors('status');
        $this->changeStatus($expense, 'cancelled', ['reason' => 'Duplicate bill', 'void_payments' => true])->assertSessionHasNoErrors();
        $this->assertSame(ExpenseStatus::Cancelled, $expense->fresh()->status);
        $this->assertNotNull($expense->fresh()->cancelled_at);
        $this->assertDatabaseCount('expense_payments', 1); $this->assertDatabaseCount('settlements', 1);
        $this->assertSame([0, 0], array_column((new TripBalances)->calculate($this->trip), 'net_minor'));
    }

    public function test_share_paid_rejects_non_admin_foreign_members_unfunded_and_cancelled_expenses(): void
    {
        $expense = $this->createExpense(false); $url = "/trips/{$this->trip->id}/expenses/{$expense->id}/shares/paid";
        $this->actingAs($this->friend)->post($url, ['member_id' => $this->friendMember, 'version' => 1])->assertForbidden();
        $this->actingAs($this->owner)->post($url, ['member_id' => $this->friendMember, 'version' => 1])->assertSessionHasErrors('share');
        $this->changeStatus($expense, 'cancelled', ['reason' => 'Not needed'])->assertSessionHasNoErrors();
        $this->post($url, ['member_id' => $this->friendMember, 'version' => $expense->fresh()->version])->assertSessionHasErrors('share');
        $paid = $this->createExpense();
        $this->post("/trips/{$this->trip->id}/expenses/{$paid->id}/shares/paid", ['member_id' => 999999, 'version' => 1])->assertSessionHasErrors('share');
        $this->assertDatabaseCount('settlements', 0);
    }

    public function test_share_reimbursement_can_be_recorded_after_closing(): void
    {
        $expense = $this->createExpense(); $this->changeStatus($expense, 'completed'); $this->changeStatus($expense, 'closed');
        $this->post("/trips/{$this->trip->id}/expenses/{$expense->id}/shares/paid", ['member_id' => $this->friendMember, 'version' => $expense->fresh()->version])->assertSessionHasNoErrors();
        $this->assertSame(ExpenseStatus::Closed, $expense->fresh()->status);
        $this->assertSame([0, 0], array_column((new TripBalances)->calculate($this->trip), 'net_minor'));
    }

    public function test_payer_can_confirm_existing_reimbursement_without_being_organizer(): void
    {
        $expense = $this->createExpense();
        $this->trip->update(['organizer_user_id' => $this->friend->id]);
        $settlement = $this->trip->settlements()->create(['from_member_id' => $this->friendMember, 'to_member_id' => $this->ownerMember, 'amount_minor' => 5000, 'currency_code' => 'PHP', 'payment_method' => 'gcash', 'status' => 'pending', 'paid_at' => now()]);
        $this->post("/trips/{$this->trip->id}/expenses/{$expense->id}/shares/paid", ['member_id' => $this->friendMember, 'version' => 1, 'settlement_id' => $settlement->id])->assertSessionHasNoErrors();
        $this->assertDatabaseCount('settlements', 1);
        $this->assertSame($expense->id, $settlement->fresh()->expense_id);
        $this->assertSame('confirmed', $settlement->fresh()->status);
        $this->assertSame([0, 0], array_column((new TripBalances)->calculate($this->trip), 'net_minor'));
    }

    public function test_applying_confirmed_reimbursement_keeps_total_money_unchanged(): void
    {
        $expense = $this->createExpense(); $this->changeStatus($expense, 'completed');
        $settlement = $this->trip->settlements()->create(['from_member_id' => $this->friendMember, 'to_member_id' => $this->ownerMember, 'amount_minor' => 8000, 'currency_code' => 'PHP', 'payment_method' => 'cash', 'status' => 'confirmed', 'paid_at' => now(), 'confirmed_at' => now()]);
        $before = (new TripBalances)->calculate($this->trip);
        $this->post("/trips/{$this->trip->id}/expenses/{$expense->id}/shares/paid", ['member_id' => $this->friendMember, 'version' => $expense->fresh()->version, 'settlement_id' => $settlement->id])->assertSessionHasNoErrors();
        $this->assertSame(3000, $settlement->fresh()->amount_minor);
        $this->assertNull($settlement->fresh()->expense_id);
        $this->assertSame(8000, (int) $this->trip->settlements()->sum('amount_minor'));
        $this->assertSame($before, (new TripBalances)->calculate($this->trip));
        $this->assertSame(5000, (int) $expense->settlements()->sum('amount_minor'));
    }

    public function test_unrelated_general_reimbursement_does_not_block_share_confirmation(): void
    {
        $expense = $this->createExpense();
        $this->trip->settlements()->create(['from_member_id' => $this->ownerMember, 'to_member_id' => $this->friendMember, 'amount_minor' => 100, 'currency_code' => 'PHP', 'payment_method' => 'cash', 'status' => 'pending', 'paid_at' => now()]);
        $this->post("/trips/{$this->trip->id}/expenses/{$expense->id}/shares/paid", ['member_id' => $this->friendMember, 'version' => 1])->assertSessionHasNoErrors();
        $this->assertSame([0, 0], array_column((new TripBalances)->calculate($this->trip), 'net_minor'));
    }

    public function test_existing_payment_requires_selection_and_cannot_be_reused_on_another_expense(): void
    {
        $expense = $this->createExpense();
        $settlement = $this->trip->settlements()->create(['from_member_id' => $this->friendMember, 'to_member_id' => $this->ownerMember, 'amount_minor' => 5000, 'currency_code' => 'PHP', 'payment_method' => 'cash', 'status' => 'confirmed', 'paid_at' => now()]);
        $url = "/trips/{$this->trip->id}/expenses/{$expense->id}/shares/paid";
        $this->post($url, ['member_id' => $this->friendMember, 'version' => 1])->assertSessionHasErrors('share');
        $this->post($url, ['member_id' => $this->friendMember, 'version' => 1, 'settlement_id' => $settlement->id])->assertSessionHasNoErrors();
        $other = $this->createExpense();
        $this->post("/trips/{$this->trip->id}/expenses/{$other->id}/shares/paid", ['member_id' => $this->friendMember, 'version' => 1, 'settlement_id' => $settlement->id])->assertSessionHasErrors('share');
        $this->assertDatabaseCount('settlements', 1);
    }
}
