<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class ExpenseAllocationProposal extends Model
{
    protected $fillable = ['expense_id', 'proposed_by_member_id', 'base_version', 'proposed_allocations', 'status', 'reviewed_by_member_id', 'reviewed_at'];
    protected function casts(): array { return ['proposed_allocations' => 'array', 'reviewed_at' => 'datetime']; }
    public function expense(): BelongsTo { return $this->belongsTo(Expense::class); }
}
