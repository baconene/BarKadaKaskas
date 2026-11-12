<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration {
    public function up(): void
    {
        // The place shown on a post ("Cambugahay Falls", "San Juan, Siquijor"), guessed when posting and editable;
        // when it is an itinerary stop, the day highlight puts the post's photos at that stop.
        Schema::table('trip_posts', function (Blueprint $t) {
            $t->string('place_name', 200)->nullable();
            $t->unsignedBigInteger('itinerary_item_id')->nullable();
        });
    }

    public function down(): void
    {
        Schema::table('trip_posts', fn (Blueprint $t) => $t->dropColumn(['place_name', 'itinerary_item_id']));
    }
};
