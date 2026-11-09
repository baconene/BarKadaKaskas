<?php

namespace Tests\Feature;

use App\Models\{Trip, TravelerDocument, User};
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Http\UploadedFile;
use Illuminate\Support\Facades\Storage;
use Tests\TestCase;

class TravelerDocumentTest extends TestCase
{
    use RefreshDatabase;

    private User $organizer;
    private User $owner;
    private User $friend;
    private User $outsider;
    private Trip $trip;

    protected function setUp(): void
    {
        parent::setUp();
        Storage::fake('local');
        [$this->organizer, $this->owner, $this->friend, $this->outsider] = [User::factory()->create(), User::factory()->create(), User::factory()->create(), User::factory()->create()];
        $this->trip = Trip::create(['organizer_user_id' => $this->organizer->id, 'name' => 'Siquijor', 'destination' => 'Siquijor', 'starts_on' => '2026-11-11', 'ends_on' => '2026-11-17']);
        foreach ([$this->organizer, $this->owner, $this->friend] as $user) { $this->trip->members()->create(['user_id' => $user->id, 'status' => 'active']); }
    }

    private function headers(User $user): array
    {
        $this->app['auth']->forgetGuards();
        return ['Authorization' => 'Bearer '.$user->createToken('Test device', ['mobile'], now()->addDay())->plainTextToken];
    }

    private function upload(User $user, string $type, array $extra = []): array
    {
        return $this->post("/api/v1/trips/{$this->trip->id}/documents", ['type' => $type, 'file' => UploadedFile::fake()->image('doc.jpg', 600, 600)] + $extra, $this->headers($user) + ['Accept' => 'application/json'])->assertCreated()->json('data');
    }

    public function test_bank_qr_is_shared_and_other_documents_stay_private_by_default(): void
    {
        $qr = $this->upload($this->owner, 'bank_qr', ['label' => 'GCash']);
        $pass = $this->upload($this->owner, 'boarding_pass');
        $this->assertSame(['trip', 'private'], [$qr['visibility'], $pass['visibility']]);
        $this->assertTrue($qr['is_image']);

        // A fellow traveller sees the QR only; the organizer and the owner see both.
        $this->assertSame([$qr['id']], array_column($this->getJson("/api/v1/trips/{$this->trip->id}/documents", $this->headers($this->friend))->assertOk()->json('data'), 'id'));
        $this->assertCount(2, $this->getJson("/api/v1/trips/{$this->trip->id}/documents", $this->headers($this->organizer))->json('data'));
        $this->assertCount(2, $this->getJson("/api/v1/trips/{$this->trip->id}/documents", $this->headers($this->owner))->json('data'));

        $this->get("/api/v1{$qr['file_path']}", $this->headers($this->friend))->assertOk();
        $this->get("/api/v1{$pass['file_path']}", $this->headers($this->friend))->assertNotFound();
        $this->get("/api/v1{$pass['file_path']}", $this->headers($this->organizer))->assertOk();
        $this->getJson("/api/v1/trips/{$this->trip->id}/documents", $this->headers($this->outsider))->assertForbidden();
        $this->get("/api/v1{$qr['file_path']}", $this->headers($this->outsider))->assertForbidden();
    }

    public function test_only_the_owner_changes_or_removes_a_document(): void
    {
        $pass = $this->upload($this->owner, 'boarding_pass');
        $url = "/api/v1/trips/{$this->trip->id}/documents/{$pass['id']}";
        $this->patchJson($url, ['visibility' => 'trip'], $this->headers($this->organizer))->assertForbidden();
        $this->patchJson($url, ['visibility' => 'trip', 'label' => 'MNL to DGT'], $this->headers($this->owner))->assertOk()->assertJsonPath('data.visibility', 'trip')->assertJsonPath('data.label', 'MNL to DGT');
        $this->deleteJson($url, [], $this->headers($this->friend))->assertForbidden();
        $path = TravelerDocument::findOrFail($pass['id'])->path;
        $this->deleteJson($url, [], $this->headers($this->owner))->assertNoContent();
        Storage::disk('local')->assertMissing($path);
        $this->assertDatabaseCount('traveler_documents', 0);
    }

    public function test_only_photos_and_pdfs_are_accepted(): void
    {
        $this->post("/api/v1/trips/{$this->trip->id}/documents", ['type' => 'id', 'file' => UploadedFile::fake()->create('notes.txt', 5, 'text/plain')], $this->headers($this->owner) + ['Accept' => 'application/json'])->assertJsonValidationErrors('file');
        $this->post("/api/v1/trips/{$this->trip->id}/documents", ['type' => 'passport', 'file' => UploadedFile::fake()->create('pass.pdf', 50, 'application/pdf')], $this->headers($this->owner) + ['Accept' => 'application/json'])->assertJsonValidationErrors('type');
        $pdf = $this->post("/api/v1/trips/{$this->trip->id}/documents", ['type' => 'booking', 'file' => UploadedFile::fake()->create('booking.pdf', 50, 'application/pdf')], $this->headers($this->owner) + ['Accept' => 'application/json'])->assertCreated()->json('data');
        $this->assertFalse($pdf['is_image']);
    }
}
