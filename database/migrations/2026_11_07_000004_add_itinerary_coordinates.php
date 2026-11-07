<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration {
    public function up(): void
    {
        Schema::table('itinerary_items', function (Blueprint $table) {
            // Set when a location is picked from Google place search; null for typed addresses.
            $table->decimal('latitude', 10, 7)->nullable();
            $table->decimal('longitude', 10, 7)->nullable();
        });
    }

    public function down(): void
    {
        Schema::table('itinerary_items', fn (Blueprint $table) => $table->dropColumn(['latitude', 'longitude']));
    }
};
