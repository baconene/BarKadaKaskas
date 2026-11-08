<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\{BelongsTo, HasMany};

class TripPost extends Model
{
    protected $fillable = ['trip_id', 'trip_member_id', 'caption'];
    public function trip(): BelongsTo { return $this->belongsTo(Trip::class); }
    public function member(): BelongsTo { return $this->belongsTo(TripMember::class, 'trip_member_id'); }
    public function photos(): HasMany { return $this->hasMany(TripPostPhoto::class)->orderBy('position'); }
    public function likes(): HasMany { return $this->hasMany(TripPostLike::class); }
}
