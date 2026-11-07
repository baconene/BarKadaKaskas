<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration {
    public function up(): void
    {
        Schema::table('itinerary_items', function (Blueprint $table) {
            $table->string('transport_mode', 30)->nullable();
        });
    }

    public function down(): void
    {
        Schema::table('itinerary_items', fn (Blueprint $table) => $table->dropColumn('transport_mode'));
    }
};
