<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;

class TripPostPhoto extends Model
{
    protected $fillable = ['trip_post_id', 'path', 'width', 'height', 'position', 'latitude', 'longitude', 'location_source', 'taken_at'];
    public function post(): \Illuminate\Database\Eloquent\Relations\BelongsTo { return $this->belongsTo(TripPost::class, 'trip_post_id'); }
    protected function casts(): array { return ['latitude' => 'float', 'longitude' => 'float', 'taken_at' => 'datetime']; }
}
