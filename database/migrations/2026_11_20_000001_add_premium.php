<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

/** Premium: when a user's paid AI access ends, and each PayMongo payment for it. */
return new class extends Migration
{
    public function up(): void
    {
        Schema::table('users', function (Blueprint $table) { $table->timestamp('premium_until')->nullable(); });
        Schema::create('premium_payments', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->string('checkout_session_id')->nullable()->unique();
            $table->unsignedInteger('amount_minor');
            $table->string('currency_code', 3);
            $table->string('status')->default('pending'); // pending | paid
            $table->timestamp('paid_at')->nullable();
            $table->timestamp('period_end')->nullable();
            $table->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('premium_payments');
        Schema::table('users', function (Blueprint $table) { $table->dropColumn('premium_until'); });
    }
};
