<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;

class Trip extends Model
{
    protected $fillable = ['organizer_user_id', 'name', 'description', 'destination', 'starts_on', 'ends_on', 'timezone', 'currency_code', 'status', 'cancelled_at', 'cancellation_reason', 'hidden_at', 'cover_path'];
    protected $hidden = ['cover_path'];
    protected $appends = ['cover_url'];
    protected function casts(): array { return ['starts_on' => 'date:Y-m-d', 'ends_on' => 'date:Y-m-d', 'cancelled_at' => 'datetime', 'hidden_at' => 'datetime']; }
    /** Public URL of the organizer's cover photo, or null. */
    public function getCoverUrlAttribute(): ?string { return \App\Support\PublicImages::url($this->cover_path); }
    public function isCancelled(): bool { return $this->status === 'cancelled'; }
    public function organizer(): BelongsTo { return $this->belongsTo(User::class, 'organizer_user_id'); }
    public function members(): HasMany { return $this->hasMany(TripMember::class); }
    public function expenses(): HasMany { return $this->hasMany(Expense::class); }
    public function itineraryItems(): HasMany { return $this->hasMany(ItineraryItem::class); }
    public function settlements(): HasMany { return $this->hasMany(Settlement::class); }
    public function posts(): HasMany { return $this->hasMany(TripPost::class); }
}
