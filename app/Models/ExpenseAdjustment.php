<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class ExpenseAdjustment extends Model
{
    protected $fillable = ['expense_id', 'expense_item_id', 'type', 'amount_minor', 'allocation_method'];
    protected function casts(): array { return ['amount_minor' => 'integer']; }
    public function expense(): BelongsTo { return $this->belongsTo(Expense::class); }
    public function expenseItem(): BelongsTo { return $this->belongsTo(ExpenseItem::class); }
}
