<?php

namespace App\Models;

use App\Enums\ExpenseStatus;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;

class Expense extends Model
{
    protected $fillable = ['trip_id', 'created_by_member_id', 'category_id', 'itinerary_item_id', 'title', 'merchant', 'incurred_at', 'currency_code', 'total_minor', 'split_method', 'status', 'version', 'completed_at', 'closed_at', 'cancelled_at', 'cancellation_reason'];
    protected function casts(): array { return ['status' => ExpenseStatus::class, 'incurred_at' => 'date:Y-m-d', 'total_minor' => 'integer', 'version' => 'integer', 'completed_at' => 'datetime', 'closed_at' => 'datetime', 'cancelled_at' => 'datetime']; }
    public function trip(): BelongsTo { return $this->belongsTo(Trip::class); }
    public function category(): BelongsTo { return $this->belongsTo(ExpenseCategory::class, 'category_id'); }
    public function payments(): HasMany { return $this->hasMany(ExpensePayment::class); }
    public function items(): HasMany { return $this->hasMany(ExpenseItem::class); }
    public function adjustments(): HasMany { return $this->hasMany(ExpenseAdjustment::class); }
    public function allocations(): HasMany { return $this->hasMany(ExpenseAllocation::class); }
    public function receipts(): HasMany { return $this->hasMany(Receipt::class); }
}
