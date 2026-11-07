<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class ReceiptScan extends Model
{
    protected $fillable = ['trip_id', 'trip_member_id', 'storage_path', 'mime_type', 'file_hash', 'provider', 'status', 'raw_result', 'normalized_result', 'error_message', 'expense_id'];
    protected $hidden = ['storage_path', 'raw_result'];
    protected function casts(): array { return ['raw_result' => 'array', 'normalized_result' => 'array']; }
    public function trip(): BelongsTo { return $this->belongsTo(Trip::class); }
}
