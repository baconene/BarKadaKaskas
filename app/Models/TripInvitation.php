<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class TripInvitation extends Model
{
    protected $fillable = ['trip_id', 'created_by_user_id', 'token_hash', 'expires_at', 'max_uses', 'uses_count', 'revoked_at'];
    protected function casts(): array { return ['expires_at' => 'datetime', 'revoked_at' => 'datetime']; }
    public function trip(): BelongsTo { return $this->belongsTo(Trip::class); }
}
