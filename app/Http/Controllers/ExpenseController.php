<?php
namespace App\Http\Controllers;

use App\Enums\ExpenseStatus;
use App\Models\{ActivityLog, Expense, ExpenseCategory, Receipt, Trip};
use App\Services\{ExpenseLifecycle, SplitCalculator};
use Illuminate\Http\Request;
use Illuminate\Support\Facades\{DB, Gate, Storage};
use Illuminate\Validation\{Rule, ValidationException};

class ExpenseController extends Controller
{
    public function store(Request $request, Trip $trip, SplitCalculator $splitter)
    {
        Gate::authorize('view', $trip);
        $data = $this->validatedExpense($request, $trip);
        $expense = DB::transaction(function () use ($data, $trip, $request, $splitter) {
            $expense = $trip->expenses()->create(collect($data)->except(['payments', 'allocations', 'items', 'version'])->all() + ['created_by_member_id' => $trip->members()->where('user_id', $request->user()->id)->value('id'), 'currency_code' => $trip->currency_code, 'status' => 'open']);
            foreach ($data['payments'] as $payment) { $expense->payments()->create($payment + ['paid_at' => now()]); }
            $this->writeAllocations($expense, $data, $splitter);
            ActivityLog::create(['trip_id' => $trip->id, 'actor_user_id' => $request->user()->id, 'subject_type' => 'expense', 'subject_id' => $expense->id, 'action' => 'created', 'after' => ['total_minor' => $expense->total_minor, 'status' => 'open']]);
            return $expense;
        });
        return $this->respond($request, $expense, 201);
    }

    public function update(Request $request, Trip $trip, Expense $expense, SplitCalculator $splitter)
    {
        abort_unless($expense->trip_id === $trip->id, 404);
        Gate::authorize('update', $trip);
        $request->validate(['version' => 'required|integer|min:1']);
        $data = $this->validatedExpense($request, $trip);
        DB::transaction(function () use ($data, $expense, $request, $splitter) {
            $expense = Expense::whereKey($expense->id)->lockForUpdate()->firstOrFail();
            $fail = fn ($message) => throw ValidationException::withMessages(['expense' => $message]);
            if (in_array($expense->status, [ExpenseStatus::Closed, ExpenseStatus::Cancelled], true)) { $fail('Closed and cancelled expenses cannot be edited.'); }
            if ($expense->version !== (int) $data['version']) { $fail('This expense changed. Refresh and reopen the editor before saving.'); }
            // Payments are historical facts. The editor changes shares, never payment history.
            $existing = $expense->payments()->get()->groupBy('trip_member_id')->map(fn ($rows) => (int) $rows->sum('amount_minor'))->sortKeys()->all();
            $submitted = collect($data['payments'])->mapWithKeys(fn ($p) => [(int) $p['trip_member_id'] => (int) $p['amount_minor']])->sortKeys()->all();
            if ($existing !== $submitted) { $fail('Recorded payments cannot be changed here. Use Record payment to add a payment.'); }
            if ($expense->status === ExpenseStatus::Completed && array_sum($existing) !== (int) $data['total_minor']) { $fail('A completed expense total must still equal its recorded payments. You can change its assigned shares.'); }
            if ($expense->adjustments()->exists()) { $fail('This expense has separate adjustments and requires an adjustment-aware editor.'); }
            $before = $expense->load(['items', 'allocations'])->toArray();
            $expense->allocations()->delete();
            $expense->items()->delete();
            $expense->update(collect($data)->except(['payments', 'allocations', 'items', 'version'])->all() + ['version' => $expense->version + 1]);
            $this->writeAllocations($expense, $data, $splitter);
            ActivityLog::create(['trip_id' => $expense->trip_id, 'actor_user_id' => $request->user()->id, 'subject_type' => 'expense', 'subject_id' => $expense->id, 'action' => 'updated', 'before' => $before, 'after' => $expense->fresh(['items', 'allocations'])->toArray()]);
        });
        return $this->respond($request, $expense);
    }

    private function validatedExpense(Request $request, Trip $trip): array
    {
        $memberIds = $trip->members()->where('status', 'active')->pluck('id')->all();
        $data = $request->validate([
            'version' => 'sometimes|integer|min:1',
            'title' => 'required|string|max:200', 'merchant' => 'nullable|string|max:200', 'incurred_at' => 'required|date_format:Y-m-d',
            'category_id' => ['required', Rule::in(ExpenseCategory::where(fn ($q) => $q->whereNull('trip_id')->orWhere('trip_id', $trip->id))->pluck('id')->all())],
            'total_minor' => 'required|integer|min:1|max:100000000', 'split_method' => 'required|in:equal,exact,percentage,shares,itemized',
            'payments' => 'present|array|max:100', 'payments.*.trip_member_id' => ['required', 'integer', 'distinct', Rule::in($memberIds)],
            'payments.*.amount_minor' => 'required|integer|min:1|max:100000000',
            'allocations' => 'present|array|max:100', 'allocations.*.trip_member_id' => ['required', 'integer', 'distinct', Rule::in($memberIds)],
            'allocations.*.weight' => 'required|integer|min:0|max:100000000',
            'items' => 'present|array|max:100', 'items.*.description' => 'required|string|max:200', 'items.*.amount_minor' => 'required|integer|min:1|max:100000000',
            'items.*.member_ids' => 'required|array|min:1|max:100', 'items.*.member_ids.*' => ['required', 'integer', Rule::in($memberIds)],
        ]);
        if (array_sum(array_column($data['payments'], 'amount_minor')) > $data['total_minor']) { throw ValidationException::withMessages(['payments' => 'Payments cannot exceed the total.']); }
        if ($data['split_method'] === 'itemized' && array_sum(array_column($data['items'], 'amount_minor')) !== (int) $data['total_minor']) { throw ValidationException::withMessages(['items' => 'Item totals must equal the bill total. Include taxes and discounts in reviewed line amounts.']); }
        return $data;
    }

    private function writeAllocations(Expense $expense, array $data, SplitCalculator $splitter): void
    {
            if ($data['split_method'] === 'itemized') {
                foreach ($data['items'] as $index => $line) {
                    $item = $expense->items()->create(['description' => $line['description'], 'unit_price_minor' => $line['amount_minor'], 'line_total_minor' => $line['amount_minor'], 'sort_order' => $index]);
                    foreach ($splitter->calculate((int) $line['amount_minor'], 'equal', array_fill_keys($line['member_ids'], 1)) as $member => $amount) { $expense->allocations()->create(['expense_item_id' => $item->id, 'trip_member_id' => $member, 'amount_minor' => $amount]); }
                }
            } else {
                $weights = array_map('intval', array_column($data['allocations'], 'weight', 'trip_member_id'));
                foreach ($splitter->calculate((int) $data['total_minor'], $data['split_method'], $weights) as $member => $amount) { $expense->allocations()->create(['trip_member_id' => $member, 'amount_minor' => $amount, 'basis_value' => $weights[$member]]); }
            }
    }
    public function status(Request $request, Trip $trip, Expense $expense, ExpenseLifecycle $lifecycle)
    {
        abort_unless($expense->trip_id === $trip->id, 404);
        $data = $request->validate(['status' => ['required', Rule::enum(ExpenseStatus::class)], 'version' => 'required|integer|min:1', 'reason' => 'nullable|string|max:2000', 'void_payments' => 'sometimes|boolean']);
        $lifecycle->transition($expense, $request->user(), ExpenseStatus::from($data['status']), (int) $data['version'], $data['reason'] ?? null, (bool) ($data['void_payments'] ?? false));
        return $this->respond($request, $expense);
    }
    public function sharePaid(Request $request, Trip $trip, Expense $expense, \App\Services\ExpenseShares $shares)
    {
        abort_unless($expense->trip_id === $trip->id, 404);
        $data = $request->validate(['member_id' => 'required|integer', 'version' => 'required|integer', 'settlement_id' => 'nullable|integer', 'new_payment' => 'sometimes|boolean']);
        $shares->markPaid($expense, (int) $data['member_id'], $request->user(), (int) $data['version'], isset($data['settlement_id']) ? (int) $data['settlement_id'] : null, (bool) ($data['new_payment'] ?? false));
        return $this->respond($request, $expense);
    }
    public function payment(Request $request, Trip $trip, Expense $expense)
    {
        abort_unless($expense->trip_id === $trip->id, 404); Gate::authorize('update', $trip);
        $data = $request->validate(['trip_member_id' => ['required', Rule::in($trip->members()->where('status', 'active')->pluck('id')->all())], 'amount_minor' => 'required|integer|min:1|max:100000000', 'version' => 'required|integer']);
        DB::transaction(function () use ($expense, $data, $request) {
            $expense = Expense::whereKey($expense->id)->lockForUpdate()->firstOrFail();
            if ($expense->status !== ExpenseStatus::Open || $expense->version !== (int) $data['version'] || $expense->payments()->sum('amount_minor') + $data['amount_minor'] > $expense->total_minor) { throw ValidationException::withMessages(['payment' => 'Refresh and check the open expense and remaining amount.']); }
            $payment = $expense->payments()->create(['trip_member_id' => $data['trip_member_id'], 'amount_minor' => $data['amount_minor'], 'paid_at' => now()]);
            $expense->increment('version');
            ActivityLog::create(['trip_id' => $expense->trip_id, 'actor_user_id' => $request->user()->id, 'subject_type' => 'expense', 'subject_id' => $expense->id, 'action' => 'payment_recorded', 'after' => $payment->toArray()]);
        });
        return $this->respond($request, $expense);
    }
    public function receipt(Request $request, Trip $trip, Expense $expense)
    {
        abort_unless($expense->trip_id === $trip->id, 404); Gate::authorize('view', $trip);
        $request->validate(['receipt' => 'required|file|mimes:jpg,jpeg,png,pdf|max:10240']);
        abort_if(in_array($expense->status, [ExpenseStatus::Closed, ExpenseStatus::Cancelled]), 409);
        $file = $request->file('receipt');
        $receipt = $expense->receipts()->create(['uploaded_by_member_id' => $trip->members()->where('user_id', $request->user()->id)->value('id'), 'storage_path' => $file->store('receipts', 'local'), 'mime_type' => $file->getMimeType(), 'file_hash' => hash_file('sha256', $file->getRealPath()), 'processing_status' => 'manual_review']);
        return $request->is('api/*') ? response()->json(['data' => $receipt->only(['id', 'processing_status'])], 201) : back();
    }
    public function download(Receipt $receipt)
    {
        Gate::authorize('view', $receipt->expense->trip);
        return Storage::disk('local')->download($receipt->storage_path);
    }

    private function respond(Request $request, Expense $expense, int $status = 200)
    {
        return $request->is('api/*')
            ? response()->json(['data' => $expense->fresh(['category', 'items', 'allocations', 'payments', 'receipts:id,expense_id,processing_status'])], $status)
            : back();
    }
}
