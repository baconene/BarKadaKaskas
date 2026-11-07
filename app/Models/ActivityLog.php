<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class ActivityLog extends Model
{
    protected $fillable = ['trip_id', 'actor_user_id', 'subject_type', 'subject_id', 'action', 'before', 'after'];
    protected function casts(): array { return ['before' => 'array', 'after' => 'array']; }
    public function trip(): BelongsTo { return $this->belongsTo(Trip::class); }
}
