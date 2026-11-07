<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration {
    public function up(): void
    {
        Schema::table('itinerary_items', function (Blueprint $table) {
            // Estimated spend per person for this stop, in minor units (centavos), as a range.
            $table->unsignedBigInteger('cost_min_minor')->nullable();
            $table->unsignedBigInteger('cost_max_minor')->nullable();
            $table->string('cost_note', 255)->nullable();
        });
    }

    public function down(): void
    {
        Schema::table('itinerary_items', fn (Blueprint $table) => $table->dropColumn(['cost_min_minor', 'cost_max_minor', 'cost_note']));
    }
};
