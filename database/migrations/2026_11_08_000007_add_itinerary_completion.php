<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration {
    public function up(): void
    {
        // "Done" is shared by everyone on the trip, so it is stored with the activity instead of on each device.
        Schema::table('itinerary_items', function (Blueprint $table) {
            $table->timestamp('completed_at')->nullable()->after('ends_at');
            $table->foreignId('completed_by_user_id')->nullable()->after('completed_at')->constrained('users')->nullOnDelete();
        });
    }

    public function down(): void
    {
        Schema::table('itinerary_items', function (Blueprint $table) {
            $table->dropConstrainedForeignId('completed_by_user_id');
            $table->dropColumn('completed_at');
        });
    }
};
