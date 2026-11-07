<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class ItineraryItem extends Model
{
    protected $fillable = ['trip_id', 'created_by_user_id', 'title', 'description', 'location', 'starts_at', 'ends_at', 'timezone', 'sort_order', 'transport_mode', 'latitude', 'longitude', 'cost_min_minor', 'cost_max_minor', 'cost_note'];
    protected function casts(): array { return ['starts_at' => 'datetime', 'ends_at' => 'datetime', 'latitude' => 'float', 'longitude' => 'float', 'cost_min_minor' => 'integer', 'cost_max_minor' => 'integer']; }
    public function trip(): BelongsTo { return $this->belongsTo(Trip::class); }
}
