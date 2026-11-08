<?php

namespace App\Http\Controllers;

use App\Models\{ExpenseCategory, ReceiptScan, Trip};
use App\Services\Receipts\{GeminiReceiptReader, ReceiptNormalizer, ReceiptReadException};
use Illuminate\Http\Request;
use Illuminate\Support\Facades\{Gate, Log, Storage};

/** "Scan receipt": reads a photo into an expense draft. Nothing is saved as an expense until the traveller confirms. */
class ReceiptScanController extends Controller
{
    public function store(Request $request, Trip $trip, GeminiReceiptReader $reader, ReceiptNormalizer $normalizer)
    {
        Gate::authorize('addExpense', $trip);
        $request->validate(['receipt' => 'required|file|mimes:jpg,jpeg,png,webp,heic,heif|max:10240']);
        $file = $request->file('receipt');
        $mime = $file->getMimeType() ?: 'image/jpeg';
        $memberId = $trip->members()->where('user_id', $request->user()->id)->value('id');

        // Larger models can take a while on a photo; allow more than PHP's default 30 seconds.
        @set_time_limit(150);
        try {
            $result = $reader->read((string) file_get_contents($file->getRealPath()), $mime, $trip->currency_code);
        } catch (ReceiptReadException $e) {
            return response()->json(['message' => $e->getMessage()], $e->status);
        } catch (\Illuminate\Http\Client\ConnectionException $e) {
            Log::warning('Gemini receipt scan timed out or could not connect', ['error' => $e->getMessage()]);
            return response()->json(['message' => 'The receipt reader took too long to answer. Try again, or set a faster model (Flash or Flash Lite).'], 504);
        } catch (\Throwable $e) {
            report($e);
            return response()->json(['message' => 'Something went wrong while reading the receipt ('.class_basename($e).'). Check the server log for details.'], 500);
        }

        $categories = ExpenseCategory::where('is_active', true)->where(fn ($q) => $q->whereNull('trip_id')->orWhere('trip_id', $trip->id))->get();
        $draft = $normalizer->normalize($result['raw'], $trip->currency_code, $categories);
        $scan = ReceiptScan::create([
            'trip_id' => $trip->id, 'trip_member_id' => $memberId,
            'storage_path' => $file->store('receipts/scans', 'local'), 'mime_type' => $mime, 'file_hash' => hash_file('sha256', $file->getRealPath()),
            'provider' => GeminiReceiptReader::PROVIDER.':'.$reader->model(), 'status' => 'read', 'raw_result' => $result['raw'], 'normalized_result' => $draft,
        ]);

        return response()->json(['data' => ['scan_id' => $scan->id] + $draft], 201);
    }

    /** Removes scans that were never turned into an expense. Scheduled daily. */
    public static function prune(): int
    {
        $stale = ReceiptScan::whereNull('expense_id')->where('created_at', '<', now()->subDays(2))->get();
        foreach ($stale as $scan) { Storage::disk('local')->delete($scan->storage_path); $scan->delete(); }
        return $stale->count();
    }
}
