<?php

namespace App\Http\Controllers;

use App\Models\{Trip, TravelerDocument};
use Illuminate\Http\Request;
use Illuminate\Support\Facades\{Gate, Storage};
use Illuminate\Support\Str;

/**
 * Travellers' documents for a trip: boarding passes, IDs, bank QR codes for paying each other back. Everyone on the
 * trip sees "trip" documents; "private" ones are visible only to their owner and the organizer. Files are served
 * through this controller (never by public URL), and only you can add or remove your own.
 */
class TravelerDocumentController extends Controller
{
    private const DIRECTORY = 'traveler-documents';

    public function index(Request $request, Trip $trip)
    {
        Gate::authorize('view', $trip);
        $memberId = $this->memberId($request, $trip);
        $organizer = $trip->organizer_user_id === $request->user()->id;
        $documents = TravelerDocument::where('trip_id', $trip->id)
            ->when(! $organizer, fn ($q) => $q->where(fn ($q) => $q->where('visibility', 'trip')->orWhere('trip_member_id', $memberId)))
            ->orderBy('trip_member_id')->orderByDesc('id')->get();
        return response()->json(['data' => $documents->map(fn ($d) => $this->present($d, $trip, $memberId))->values()]);
    }

    public function store(Request $request, Trip $trip)
    {
        Gate::authorize('view', $trip);
        $data = $request->validate([
            'type' => 'required|in:'.implode(',', TravelerDocument::TYPES),
            'label' => 'nullable|string|max:120',
            'visibility' => 'nullable|in:'.implode(',', TravelerDocument::VISIBILITIES),
            'file' => 'required|file|mimes:jpg,jpeg,png,webp,pdf|max:10240',
        ], ['file.mimes' => 'Upload a photo (JPG, PNG, WebP) or a PDF.', 'file.max' => 'The file can be up to 10 MB.']);
        $memberId = $this->memberId($request, $trip);
        abort_unless($memberId, 403);
        abort_if(TravelerDocument::where('trip_id', $trip->id)->where('trip_member_id', $memberId)->count() >= 20, 422, 'You can keep up to 20 documents per trip. Remove one first.');
        $file = $request->file('file');
        $mime = (string) $file->getMimeType();
        $extension = match ($mime) { 'application/pdf' => 'pdf', 'image/png' => 'png', 'image/webp' => 'webp', default => 'jpg' };
        $path = $file->storeAs(self::DIRECTORY.'/'.$trip->id, Str::random(40).'.'.$extension, 'local');
        $document = TravelerDocument::create([
            'trip_id' => $trip->id, 'trip_member_id' => $memberId, 'type' => $data['type'],
            'label' => trim((string) ($data['label'] ?? '')) ?: null,
            // A bank QR is for others to pay you, so it is shared by default; anything else stays private.
            'visibility' => $data['visibility'] ?? ($data['type'] === 'bank_qr' ? 'trip' : 'private'),
            'path' => $path, 'mime_type' => $mime, 'size' => (int) $file->getSize(),
        ]);
        return response()->json(['data' => $this->present($document, $trip, $memberId)], 201);
    }

    public function update(Request $request, Trip $trip, TravelerDocument $document)
    {
        $this->own($request, $trip, $document);
        $data = $request->validate(['label' => 'nullable|string|max:120', 'visibility' => 'sometimes|in:'.implode(',', TravelerDocument::VISIBILITIES)]);
        if (array_key_exists('label', $data)) { $data['label'] = trim((string) $data['label']) ?: null; }
        $document->update($data);
        return response()->json(['data' => $this->present($document->fresh(), $trip, $document->trip_member_id)]);
    }

    public function destroy(Request $request, Trip $trip, TravelerDocument $document)
    {
        $this->own($request, $trip, $document);
        Storage::disk('local')->delete($document->path);
        $document->delete();
        return response()->noContent();
    }

    /** The file itself, for members allowed to see it. Not cached by shared caches. */
    public function file(Request $request, Trip $trip, TravelerDocument $document)
    {
        abort_unless($document->trip_id === $trip->id, 404);
        Gate::authorize('view', $trip);
        $memberId = $this->memberId($request, $trip);
        abort_unless($this->canSee($document, $trip, $memberId, $request), 404);
        abort_unless(Storage::disk('local')->exists($document->path), 404);
        $name = Str::slug(($document->label ?: str_replace('_', ' ', $document->type))).'.'.pathinfo($document->path, PATHINFO_EXTENSION);
        return Storage::disk('local')->response($document->path, $name, ['Cache-Control' => 'private, max-age=300', 'X-Content-Type-Options' => 'nosniff'], 'inline');
    }

    private function memberId(Request $request, Trip $trip): ?int
    {
        return $trip->members()->where('user_id', $request->user()->id)->where('status', 'active')->value('id');
    }

    private function canSee(TravelerDocument $document, Trip $trip, ?int $memberId, Request $request): bool
    {
        return $document->visibility === 'trip' || $document->trip_member_id === $memberId || $trip->organizer_user_id === $request->user()->id;
    }

    private function own(Request $request, Trip $trip, TravelerDocument $document): void
    {
        abort_unless($document->trip_id === $trip->id, 404);
        Gate::authorize('view', $trip);
        abort_unless($document->trip_member_id === $this->memberId($request, $trip), 403, 'Only the traveller who added this document can change it.');
    }

    private function present(TravelerDocument $document, Trip $trip, ?int $memberId): array
    {
        return [
            'id' => $document->id, 'member_id' => $document->trip_member_id, 'type' => $document->type, 'label' => $document->label,
            'visibility' => $document->visibility, 'mime_type' => $document->mime_type, 'size' => $document->size,
            'is_image' => str_starts_with($document->mime_type, 'image/'), 'mine' => $document->trip_member_id === $memberId,
            // Relative path: the web app uses it with its session, the mobile apps with their API base and token.
            'file_path' => "/trips/{$trip->id}/documents/{$document->id}/file",
            'created_at' => $document->created_at?->toIso8601String(),
        ];
    }
}
