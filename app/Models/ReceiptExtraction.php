<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class ReceiptExtraction extends Model
{
    protected $fillable = ['receipt_id', 'provider', 'status', 'raw_result', 'normalized_result', 'confidence', 'error_message', 'reviewed_by_member_id', 'reviewed_at'];
    protected function casts(): array { return ['raw_result' => 'array', 'normalized_result' => 'array', 'confidence' => 'decimal:4', 'reviewed_at' => 'datetime']; }
    public function receipt(): BelongsTo { return $this->belongsTo(Receipt::class); }
}
