<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class ExpensePayment extends Model
{
    protected $fillable = ['expense_id', 'trip_member_id', 'amount_minor', 'paid_at'];
    protected function casts(): array { return ['amount_minor' => 'integer', 'paid_at' => 'datetime']; }
    public function expense(): BelongsTo { return $this->belongsTo(Expense::class); }
    public function tripMember(): BelongsTo { return $this->belongsTo(TripMember::class); }
}
