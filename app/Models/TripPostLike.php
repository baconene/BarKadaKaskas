<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;

class TripPostLike extends Model
{
    protected $fillable = ['trip_post_id', 'trip_member_id'];
}
