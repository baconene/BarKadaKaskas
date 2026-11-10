<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class TripPostComment extends Model
{
    protected $fillable = ['trip_post_id', 'trip_member_id', 'body'];
    public function post(): BelongsTo { return $this->belongsTo(TripPost::class, 'trip_post_id'); }
    public function member(): BelongsTo { return $this->belongsTo(TripMember::class, 'trip_member_id'); }
}
