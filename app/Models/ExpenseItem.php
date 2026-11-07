<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class ExpenseItem extends Model
{
    protected $fillable = ['expense_id', 'description', 'quantity', 'unit_price_minor', 'line_total_minor', 'category_id', 'sort_order'];
    protected function casts(): array { return ['quantity' => 'decimal:3', 'unit_price_minor' => 'integer', 'line_total_minor' => 'integer']; }
    public function expense(): BelongsTo { return $this->belongsTo(Expense::class); }
}
