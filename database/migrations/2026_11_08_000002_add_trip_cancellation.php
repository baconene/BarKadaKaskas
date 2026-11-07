<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration {
    public function up(): void
    {
        Schema::table('trips', function (Blueprint $table) {
            $table->timestamp('cancelled_at')->nullable();
            $table->string('cancellation_reason', 500)->nullable();
            // Set when the organizer hides a cancelled trip from everyone's trip list.
            $table->timestamp('hidden_at')->nullable();
        });
    }

    public function down(): void
    {
        Schema::table('trips', fn (Blueprint $table) => $table->dropColumn(['cancelled_at', 'cancellation_reason', 'hidden_at']));
    }
};
