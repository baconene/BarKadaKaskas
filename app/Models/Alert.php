<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class Alert extends Model
{
    public const TYPES = ['comment', 'post', 'expense', 'itinerary'];

    protected $fillable = ['user_id', 'trip_id', 'actor_user_id', 'type', 'title', 'body', 'data', 'read_at'];
    protected function casts(): array { return ['data' => 'array', 'read_at' => 'datetime']; }
    public function trip(): BelongsTo { return $this->belongsTo(Trip::class); }
    public function actor(): BelongsTo { return $this->belongsTo(User::class, 'actor_user_id'); }
}
