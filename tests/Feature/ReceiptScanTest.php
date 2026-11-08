<?php

namespace Tests\Feature;

use App\Models\{ExpenseCategory, ReceiptExtraction, ReceiptScan, Trip, User};
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Http\UploadedFile;
use Illuminate\Support\Facades\{Http, Storage};
use Tests\TestCase;

class ReceiptScanTest extends TestCase
{
    use RefreshDatabase;

    private User $owner;
    private User $friend;
    private Trip $trip;
    private int $ownerMember;
    private int $friendMember;

    protected function setUp(): void
    {
        parent::setUp();
        $this->withoutVite();
        Storage::fake('local');
        config(['services.gemini.api_key' => 'test-key', 'services.gemini.credentials' => null, 'services.gemini.model' => 'gemini-2.5-flash']);
        $this->owner = User::factory()->create(); $this->friend = User::factory()->create();
        $this->trip = Trip::create(['organizer_user_id' => $this->owner->id, 'name' => 'Siquijor', 'destination' => 'Siquijor', 'starts_on' => '2026-11-11', 'ends_on' => '2026-11-17', 'timezone' => 'Asia/Manila', 'currency_code' => 'PHP']);
        $this->ownerMember = $this->trip->members()->create(['user_id' => $this->owner->id, 'status' => 'active'])->id;
        $this->friendMember = $this->trip->members()->create(['user_id' => $this->friend->id, 'status' => 'active'])->id;
        ExpenseCategory::create(['name' => 'Food', 'is_active' => true]);
    }

    private function headers(User $user): array
    {
        $this->app['auth']->forgetGuards();
        return ['Authorization' => 'Bearer '.$user->createToken('Test device', ['mobile'], now()->addDay())->plainTextToken];
    }

    private function fakeGemini(array $receipt): void
    {
        Http::fake(['aiplatform.googleapis.com/*' => Http::response(['candidates' => [['content' => ['parts' => [['text' => json_encode($receipt)]]], 'finishReason' => 'STOP']]])]);
    }

    private function photo(): UploadedFile
    {
        return UploadedFile::fake()->image('receipt.jpg', 800, 1400);
    }

    public function test_scan_returns_a_draft_whose_lines_match_the_total(): void
    {
        $this->fakeGemini(['is_receipt' => true, 'merchant' => 'Baha Ba\'r', 'date' => '2026-11-12', 'currency' => 'PHP', 'category' => 'food', 'items' => [['description' => 'Sisig', 'quantity' => 1, 'amount' => 280], ['description' => 'Halo-halo', 'quantity' => 2, 'amount' => 300]], 'service_charge' => 58, 'tax_added' => 0, 'discount' => 0, 'total' => 638, 'notes' => null]);

        $data = $this->postJson("/api/v1/trips/{$this->trip->id}/receipt-scans", ['receipt' => $this->photo()], $this->headers($this->friend))->assertCreated()->json('data');

        $this->assertSame('Baha Ba\'r', $data['merchant']);
        $this->assertSame(63800, $data['total_minor']);
        $this->assertSame(63800, array_sum(array_column($data['lines'], 'amount_minor')));
        $this->assertArrayNotHasKey('storage_path', $data);
        Http::assertSent(fn ($request) => str_contains($request->url(), 'publishers/google/models/gemini-2.5-flash:generateContent')
            && $request['generationConfig']['responseMimeType'] === 'application/json'
            && $request['contents'][0]['parts'][0]['inlineData']['mimeType'] === 'image/jpeg');
    }

    public function test_saving_the_expense_attaches_the_scanned_receipt(): void
    {
        $this->fakeGemini(['is_receipt' => true, 'merchant' => 'Jollibee', 'category' => 'food', 'items' => [['description' => 'Chickenjoy', 'amount' => 500]], 'total' => 500]);
        $scanId = $this->postJson("/api/v1/trips/{$this->trip->id}/receipt-scans", ['receipt' => $this->photo()], $this->headers($this->owner))->json('data.scan_id');

        $expense = $this->postJson("/api/v1/trips/{$this->trip->id}/expenses", [
            'title' => 'Jollibee', 'merchant' => 'Jollibee', 'category_id' => ExpenseCategory::first()->id, 'incurred_at' => '2026-11-12', 'total_minor' => 50000,
            'split_method' => 'itemized', 'payments' => [], 'allocations' => [],
            'items' => [['description' => 'Chickenjoy', 'amount_minor' => 50000, 'member_ids' => [$this->ownerMember, $this->friendMember]]],
            'receipt_scan_id' => $scanId,
        ], $this->headers($this->owner))->assertCreated()->json('data');

        $this->assertCount(1, $expense['receipts']);
        $this->assertSame('extracted', $expense['receipts'][0]['processing_status']);
        $this->assertSame($expense['id'], ReceiptScan::find($scanId)->expense_id);
        $this->assertSame('reviewed', ReceiptExtraction::first()->status);
    }

    public function test_someone_elses_scan_is_not_attached(): void
    {
        $this->fakeGemini(['is_receipt' => true, 'category' => 'food', 'items' => [], 'total' => 100]);
        $scanId = $this->postJson("/api/v1/trips/{$this->trip->id}/receipt-scans", ['receipt' => $this->photo()], $this->headers($this->friend))->json('data.scan_id');
        $this->postJson("/api/v1/trips/{$this->trip->id}/expenses", ['title' => 'X', 'category_id' => ExpenseCategory::first()->id, 'incurred_at' => '2026-11-12', 'total_minor' => 10000, 'split_method' => 'equal', 'payments' => [], 'allocations' => [['trip_member_id' => $this->ownerMember, 'weight' => 1]], 'items' => [], 'receipt_scan_id' => $scanId], $this->headers($this->owner))
            ->assertCreated()->assertJsonCount(0, 'data.receipts');
        $this->assertNull(ReceiptScan::find($scanId)->expense_id);
    }

    public function test_not_a_receipt_unconfigured_and_upstream_errors_are_friendly(): void
    {
        // Fakes stack, so give both replies in order: first "not a receipt", then Google refusing.
        Http::fake(['aiplatform.googleapis.com/*' => Http::sequence()
            ->push(['candidates' => [['content' => ['parts' => [['text' => json_encode(['is_receipt' => false, 'category' => 'other', 'items' => [], 'total' => null])]]]]]])
            ->push(['error' => ['status' => 'PERMISSION_DENIED', 'message' => 'nope']], 403)]);
        $this->postJson("/api/v1/trips/{$this->trip->id}/receipt-scans", ['receipt' => $this->photo()], $this->headers($this->owner))->assertUnprocessable()->assertJsonPath('message', 'That doesn’t look like a receipt. Try a photo of the whole receipt.');
        $this->postJson("/api/v1/trips/{$this->trip->id}/receipt-scans", ['receipt' => $this->photo()], $this->headers($this->owner))->assertStatus(502);

        config(['services.gemini.api_key' => null]);
        $this->postJson("/api/v1/trips/{$this->trip->id}/receipt-scans", ['receipt' => $this->photo()], $this->headers($this->owner))->assertStatus(503);
        $this->assertSame(0, ReceiptScan::count());
    }

    public function test_only_members_of_an_active_trip_can_scan(): void
    {
        $this->fakeGemini(['is_receipt' => true, 'category' => 'food', 'items' => [], 'total' => 100]);
        $stranger = User::factory()->create();
        $this->postJson("/api/v1/trips/{$this->trip->id}/receipt-scans", ['receipt' => $this->photo()], $this->headers($stranger))->assertForbidden();
        $this->trip->update(['status' => 'cancelled']);
        $this->postJson("/api/v1/trips/{$this->trip->id}/receipt-scans", ['receipt' => $this->photo()], $this->headers($this->owner))->assertForbidden();
        $this->postJson("/api/v1/trips/{$this->trip->id}/receipt-scans", ['receipt' => UploadedFile::fake()->create('a.pdf', 10, 'application/pdf')], $this->headers($this->friend))->assertForbidden();
    }

    public function test_unused_scans_are_pruned(): void
    {
        $this->fakeGemini(['is_receipt' => true, 'category' => 'food', 'items' => [], 'total' => 100]);
        $this->postJson("/api/v1/trips/{$this->trip->id}/receipt-scans", ['receipt' => $this->photo()], $this->headers($this->owner))->assertCreated();
        $scan = ReceiptScan::first();
        $this->travel(3)->days();
        $this->artisan('receipts:prune-scans')->assertSuccessful();
        $this->assertNull($scan->fresh());
        Storage::disk('local')->assertMissing($scan->storage_path);
    }

    public function test_a_slow_or_unreachable_reader_returns_a_clear_timeout(): void
    {
        Http::fake(fn () => throw new \Illuminate\Http\Client\ConnectionException('cURL error 28: Operation timed out'));
        $this->postJson("/api/v1/trips/{$this->trip->id}/receipt-scans", ['receipt' => $this->photo()], $this->headers($this->owner))
            ->assertStatus(504)->assertJsonPath('message', 'The receipt reader took too long to answer. Try again, or set a faster model (Flash or Flash Lite).');
    }

    public function test_minimal_thinking_is_requested_and_dropped_when_a_model_rejects_it(): void
    {
        $ok = ['candidates' => [['content' => ['parts' => [['text' => json_encode(['is_receipt' => true, 'category' => 'food', 'items' => [], 'total' => 100])]]]]]];
        Http::fake(['aiplatform.googleapis.com/*' => Http::sequence()
            ->push(['error' => ['status' => 'INVALID_ARGUMENT', 'message' => 'Thinking budget is not supported for this model.']], 400)
            ->push($ok)->push($ok)]);

        $this->postJson("/api/v1/trips/{$this->trip->id}/receipt-scans", ['receipt' => $this->photo()], $this->headers($this->owner))->assertCreated();
        $this->postJson("/api/v1/trips/{$this->trip->id}/receipt-scans", ['receipt' => $this->photo()], $this->headers($this->owner))->assertCreated();

        $sent = Http::recorded()->map(fn ($pair) => isset($pair[0]['generationConfig']['thinkingConfig']))->all();
        $this->assertSame([true, false, false], $sent); // the second scan remembers and skips the rejected setting
    }

    public function test_api_key_with_a_project_uses_the_global_location(): void
    {
        config(['services.gemini.project' => '1019187888243', 'services.gemini.location' => 'global', 'services.gemini.model' => 'gemini-4-argon']);
        $this->fakeGemini(['is_receipt' => true, 'category' => 'food', 'items' => [], 'total' => 100]);
        $this->postJson("/api/v1/trips/{$this->trip->id}/receipt-scans", ['receipt' => $this->photo()], $this->headers($this->owner))->assertCreated();
        Http::assertSent(fn ($request) => str_starts_with($request->url(), 'https://aiplatform.googleapis.com/v1/projects/1019187888243/locations/global/publishers/google/models/gemini-4-argon:generateContent?key=test-key'));
    }

    public function test_unavailable_model_explains_what_to_check(): void
    {
        Http::fake(['aiplatform.googleapis.com/*' => Http::response(['error' => ['status' => 'NOT_FOUND', 'message' => 'Publisher model was not found']], 404)]);
        $this->postJson("/api/v1/trips/{$this->trip->id}/receipt-scans", ['receipt' => $this->photo()], $this->headers($this->owner))
            ->assertStatus(502)->assertJsonPath('message', fn ($m) => str_contains($m, 'VERTEX_LOCATION'));
    }
}
