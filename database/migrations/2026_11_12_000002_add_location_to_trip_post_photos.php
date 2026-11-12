<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration {
    public function up(): void
    {
        // Where and when each photo was taken (from the photo's own GPS data, else the phone's location when posting),
        // so day highlights put photos at the right stop.
        Schema::table('trip_post_photos', function (Blueprint $t) {
            $t->decimal('latitude', 10, 7)->nullable();
            $t->decimal('longitude', 10, 7)->nullable();
            $t->string('location_source', 10)->nullable();   // photo | device
            $t->timestamp('taken_at')->nullable();
        });
    }

    public function down(): void
    {
        Schema::table('trip_post_photos', fn (Blueprint $t) => $t->dropColumn(['latitude', 'longitude', 'location_source', 'taken_at']));
    }
};
