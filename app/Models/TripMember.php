<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class TripMember extends Model
{
    protected $fillable = ['trip_id', 'user_id', 'status', 'joined_at', 'left_at'];
    protected function casts(): array { return ['joined_at' => 'datetime', 'left_at' => 'datetime']; }
    public function trip(): BelongsTo { return $this->belongsTo(Trip::class); }
    public function user(): BelongsTo { return $this->belongsTo(User::class); }
}
