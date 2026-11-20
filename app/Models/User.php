<?php

namespace App\Models;

// use Illuminate\Contracts\Auth\MustVerifyEmail;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Foundation\Auth\User as Authenticatable;
use Illuminate\Notifications\Notifiable;
use Laravel\Sanctum\HasApiTokens;

class User extends Authenticatable
{
    /** @use HasFactory<\Database\Factories\UserFactory> */
    use HasApiTokens, HasFactory, Notifiable;

    /**
     * The attributes that are mass assignable.
     *
     * @var list<string>
     */
    protected $fillable = [
        'name',
        'email',
        'password',
        'phone',
        'other_contact',
        'emergency_contact_name',
        'emergency_contact_relationship',
        'emergency_contact_phone',
        'avatar_path',
    ];

    /** Profile picture URL (see App\Support\PublicImages). */
    protected $appends = ['avatar_url'];

    /**
     * The attributes that should be hidden for serialization.
     *
     * @var list<string>
     */
    protected $hidden = [
        'password',
        'remember_token',
        'avatar_path',
        'paymongo_customer_id',
        'paymongo_payment_method_id',
    ];

    /**
     * Get the attributes that should be cast.
     *
     * @return array<string, string>
     */
    protected function casts(): array
    {
        return [
            'email_verified_at' => 'datetime',
            'password' => 'hashed',
            'premium_until' => 'datetime',
            'trial_started_at' => 'datetime',
            'next_renewal_attempt_at' => 'datetime',
            'terms_accepted_at' => 'datetime',
            'auto_renew' => 'boolean',
        ];
    }

    public function getAvatarUrlAttribute(): ?string
    {
        return \App\Support\PublicImages::url($this->avatar_path);
    }

    public function premiumPayments(): \Illuminate\Database\Eloquent\Relations\HasMany
    {
        return $this->hasMany(PremiumPayment::class);
    }
}
