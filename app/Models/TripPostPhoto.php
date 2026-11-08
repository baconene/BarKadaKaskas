<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;

class TripPostPhoto extends Model
{
    protected $fillable = ['trip_post_id', 'path', 'width', 'height', 'position'];
}
