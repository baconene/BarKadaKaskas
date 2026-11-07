<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;

class Trip extends Model
{
    protected $fillable = ['organizer_user_id', 'name', 'description', 'destination', 'starts_on', 'ends_on', 'timezone', 'currency_code', 'status'];
    protected function casts(): array { return ['starts_on' => 'date:Y-m-d', 'ends_on' => 'date:Y-m-d']; }
    public function organizer(): BelongsTo { return $this->belongsTo(User::class, 'organizer_user_id'); }
    public function members(): HasMany { return $this->hasMany(TripMember::class); }
    public function expenses(): HasMany { return $this->hasMany(Expense::class); }
    public function itineraryItems(): HasMany { return $this->hasMany(ItineraryItem::class); }
    public function settlements(): HasMany { return $this->hasMany(Settlement::class); }
}
