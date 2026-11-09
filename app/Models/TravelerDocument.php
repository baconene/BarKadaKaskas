<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class TravelerDocument extends Model
{
    public const TYPES = ['boarding_pass', 'id', 'bank_qr', 'booking', 'other'];
    /** "trip": everyone on the trip; "private": the owner and the organizer only. */
    public const VISIBILITIES = ['trip', 'private'];

    protected $fillable = ['trip_id', 'trip_member_id', 'type', 'label', 'visibility', 'path', 'mime_type', 'size'];
    protected $hidden = ['path'];
    protected function casts(): array { return ['size' => 'integer']; }
    public function trip(): BelongsTo { return $this->belongsTo(Trip::class); }
    public function member(): BelongsTo { return $this->belongsTo(TripMember::class, 'trip_member_id'); }
}
