<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class Receipt extends Model
{
    protected $fillable = ['expense_id', 'uploaded_by_member_id', 'storage_path', 'mime_type', 'file_hash', 'processing_status'];
    protected function casts(): array { return []; }
    public function expense(): BelongsTo { return $this->belongsTo(Expense::class); }
}
