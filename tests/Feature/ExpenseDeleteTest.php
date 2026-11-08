<?php

namespace Tests\Feature;

use App\Models\{ActivityLog, Expense, ExpenseCategory, Trip, User};
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Http\UploadedFile;
use Illuminate\Support\Facades\Storage;
use Tests\TestCase;

class ExpenseDeleteTest extends TestCase
{
    use RefreshDatabase;

    private User $owner;
    private User $friend;
    private User $other;
    private Trip $trip;
    private int $ownerMember;
    private int $friendMember;
    private int $category;

    protected function setUp(): void
    {
        parent::setUp();
        $this->withoutVite();
        Storage::fake('local');
        [$this->owner, $this->friend, $this->other] = [User::factory()->create(), User::factory()->create(), User::factory()->create()];
        $this->trip = Trip::create(['organizer_user_id' => $this->owner->id, 'name' => 'Siquijor', 'destination' => 'Siquijor', 'starts_on' => '2026-11-11', 'ends_on' => '2026-11-17', 'timezone' => 'Asia/Manila']);
        $this->ownerMember = $this->trip->members()->create(['user_id' => $this->owner->id, 'status' => 'active'])->id;
        $this->friendMember = $this->trip->members()->create(['user_id' => $this->friend->id, 'status' => 'active'])->id;
        $this->trip->members()->create(['user_id' => $this->other->id, 'status' => 'active']);
        $this->category = ExpenseCategory::create(['name' => 'Food'])->id;
    }

    private function headers(User $user): array
    {
        $this->app['auth']->forgetGuards();
        return ['Authorization' => 'Bearer '.$user->createToken('Test device', ['mobile'], now()->addDay())->plainTextToken];
    }

    private function expense(User $by, array $payments = []): array
    {
        return $this->postJson("/api/v1/trips/{$this->trip->id}/expenses", ['title' => 'Dinner', 'merchant' => 'Cafe', 'category_id' => $this->category, 'incurred_at' => '2026-11-12', 'total_minor' => 1000, 'split_method' => 'equal', 'payments' => $payments, 'allocations' => [['trip_member_id' => $this->ownerMember, 'weight' => 1], ['trip_member_id' => $this->friendMember, 'weight' => 1]], 'items' => []], $this->headers($by))->assertCreated()->json('data');
    }

    public function test_creator_and_organizer_can_delete_an_unpaid_expense_but_others_cannot(): void
    {
        $byFriend = $this->expense($this->friend);
        $this->deleteJson("/api/v1/trips/{$this->trip->id}/expenses/{$byFriend['id']}", [], $this->headers($this->other))->assertForbidden();
        $this->deleteJson("/api/v1/trips/{$this->trip->id}/expenses/{$byFriend['id']}", [], $this->headers($this->friend))->assertNoContent();
        $this->assertNull(Expense::find($byFriend['id']));
        $this->assertTrue(ActivityLog::where('action', 'deleted')->where('subject_id', $byFriend['id'])->exists());

        $byFriendAgain = $this->expense($this->friend);
        $this->deleteJson("/api/v1/trips/{$this->trip->id}/expenses/{$byFriendAgain['id']}", [], $this->headers($this->owner))->assertNoContent();
    }

    public function test_deleting_removes_receipts_and_their_files(): void
    {
        $expense = $this->expense($this->owner);
        $this->postJson("/api/v1/trips/{$this->trip->id}/expenses/{$expense['id']}/receipts", ['receipt' => UploadedFile::fake()->image('r.jpg')], $this->headers($this->owner))->assertCreated();
        $path = \App\Models\Receipt::first()->storage_path;
        Storage::disk('local')->assertExists($path);
        $this->deleteJson("/api/v1/trips/{$this->trip->id}/expenses/{$expense['id']}", [], $this->headers($this->owner))->assertNoContent();
        $this->assertSame(0, \App\Models\Receipt::count());
        Storage::disk('local')->assertMissing($path);
    }

    public function test_paid_expense_must_be_cancelled_not_deleted(): void
    {
        $expense = $this->expense($this->owner, [['trip_member_id' => $this->ownerMember, 'amount_minor' => 1000]]);
        $this->deleteJson("/api/v1/trips/{$this->trip->id}/expenses/{$expense['id']}", [], $this->headers($this->owner))->assertUnprocessable()->assertJsonValidationErrors('expense');
        $this->assertNotNull(Expense::find($expense['id']));
    }

    public function test_creator_can_cancel_their_own_expense(): void
    {
        $expense = $this->expense($this->friend);
        $this->patchJson("/api/v1/trips/{$this->trip->id}/expenses/{$expense['id']}/status", ['status' => 'cancelled', 'version' => $expense['version'], 'reason' => 'Duplicate'], $this->headers($this->other))->assertForbidden();
        $this->patchJson("/api/v1/trips/{$this->trip->id}/expenses/{$expense['id']}/status", ['status' => 'cancelled', 'version' => $expense['version'], 'reason' => 'Duplicate'], $this->headers($this->friend))->assertOk()->assertJsonPath('data.status', 'cancelled');
    }

    public function test_creator_cannot_complete_expenses(): void
    {
        $expense = $this->expense($this->friend, [['trip_member_id' => $this->friendMember, 'amount_minor' => 1000]]);
        $this->patchJson("/api/v1/trips/{$this->trip->id}/expenses/{$expense['id']}/status", ['status' => 'completed', 'version' => $expense['version']], $this->headers($this->friend))->assertForbidden();
    }
}
