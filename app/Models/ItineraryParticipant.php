<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class ItineraryParticipant extends Model
{
    protected $fillable = ['itinerary_item_id', 'trip_member_id', 'status'];
    protected function casts(): array { return []; }
    public function itineraryItem(): BelongsTo { return $this->belongsTo(ItineraryItem::class); }
    public function tripMember(): BelongsTo { return $this->belongsTo(TripMember::class); }
}
