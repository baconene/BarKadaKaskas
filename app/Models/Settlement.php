<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class Settlement extends Model
{
    protected $fillable = ['trip_id', 'expense_id', 'applied_from_id', 'from_member_id', 'to_member_id', 'amount_minor', 'currency_code', 'payment_method', 'status', 'paid_at', 'confirmed_at'];
    public function expense(): BelongsTo { return $this->belongsTo(Expense::class); }
    protected function casts(): array { return ['amount_minor' => 'integer', 'paid_at' => 'datetime', 'confirmed_at' => 'datetime']; }
    public function trip(): BelongsTo { return $this->belongsTo(Trip::class); }
}
