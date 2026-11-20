<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

/** One PayMongo checkout for a month of Premium. */
class PremiumPayment extends Model
{
    protected $fillable = ['user_id', 'checkout_session_id', 'amount_minor', 'currency_code', 'status', 'paid_at', 'period_end', 'kind', 'payment_intent_id', 'failure_reason'];
    protected function casts(): array { return ['paid_at' => 'datetime', 'period_end' => 'datetime', 'amount_minor' => 'integer']; }
    public function user(): BelongsTo { return $this->belongsTo(User::class); }
}
