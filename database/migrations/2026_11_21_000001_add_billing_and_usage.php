<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

/**
 * Subscriptions with a free trial (a saved card charged monthly until cancelled) and monthly usage allowances.
 */
return new class extends Migration
{
    public function up(): void
    {
        Schema::table('users', function (Blueprint $table) {
            // none | trialing | active | past_due | canceled
            $table->string('billing_status')->default('none');
            $table->boolean('auto_renew')->default(false);
            $table->timestamp('trial_started_at')->nullable();
            $table->string('paymongo_customer_id')->nullable();
            $table->string('paymongo_payment_method_id')->nullable();
            $table->string('card_brand', 20)->nullable();
            $table->string('card_last4', 4)->nullable();
            $table->unsignedTinyInteger('renewal_attempts')->default(0);
            $table->timestamp('next_renewal_attempt_at')->nullable();
            $table->timestamp('terms_accepted_at')->nullable();
        });
        Schema::table('premium_payments', function (Blueprint $table) {
            // checkout (one month paid by hand) | verification (card hold for the trial) | renewal (automatic charge)
            $table->string('kind')->default('checkout');
            $table->string('payment_intent_id')->nullable()->index();
            $table->string('failure_reason')->nullable();
        });
        Schema::create('usage_counters', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->string('feature', 40);
            $table->string('period', 7); // YYYY-MM
            $table->unsignedInteger('used')->default(0);
            $table->timestamps();
            $table->unique(['user_id', 'feature', 'period']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('usage_counters');
        Schema::table('premium_payments', function (Blueprint $table) { $table->dropColumn(['kind', 'payment_intent_id', 'failure_reason']); });
        Schema::table('users', function (Blueprint $table) {
            $table->dropColumn(['billing_status', 'auto_renew', 'trial_started_at', 'paymongo_customer_id', 'paymongo_payment_method_id', 'card_brand', 'card_last4', 'renewal_attempts', 'next_renewal_attempt_at', 'terms_accepted_at']);
        });
    }
};
