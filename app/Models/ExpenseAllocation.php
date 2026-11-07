<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class ExpenseAllocation extends Model
{
    protected $fillable = ['expense_id', 'expense_item_id', 'expense_adjustment_id', 'trip_member_id', 'amount_minor', 'basis_value'];
    protected function casts(): array { return ['amount_minor' => 'integer', 'basis_value' => 'decimal:4']; }
    public function expense(): BelongsTo { return $this->belongsTo(Expense::class); }
    public function expenseItem(): BelongsTo { return $this->belongsTo(ExpenseItem::class); }
    public function expenseAdjustment(): BelongsTo { return $this->belongsTo(ExpenseAdjustment::class); }
    public function tripMember(): BelongsTo { return $this->belongsTo(TripMember::class); }
}
