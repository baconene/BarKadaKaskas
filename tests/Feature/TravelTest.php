<?php
namespace Tests\Feature;

use App\Enums\ExpenseStatus;
use App\Models\{Expense, ExpenseCategory, Settlement, Trip, User};
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
}
