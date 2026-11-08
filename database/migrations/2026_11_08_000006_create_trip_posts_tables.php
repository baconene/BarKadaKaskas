<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration {
    public function up(): void
    {
        // The trip's photo feed: posts with one or more photos and an optional caption.
        Schema::create('trip_posts', function (Blueprint $t) {
            $t->id();
            $t->foreignId('trip_id')->constrained();
            $t->foreignId('trip_member_id')->constrained();
            $t->text('caption')->nullable();
            $t->timestamps();
            $t->index(['trip_id', 'id']);
        });
        Schema::create('trip_post_photos', function (Blueprint $t) {
            $t->id();
            $t->foreignId('trip_post_id')->constrained();
            $t->string('path');
            $t->unsignedInteger('width')->nullable(); $t->unsignedInteger('height')->nullable();
            $t->unsignedSmallInteger('position')->default(0);
            $t->timestamps();
        });
        Schema::create('trip_post_likes', function (Blueprint $t) {
            $t->id();
            $t->foreignId('trip_post_id')->constrained();
            $t->foreignId('trip_member_id')->constrained();
            $t->timestamps();
            $t->unique(['trip_post_id', 'trip_member_id']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('trip_post_likes');
        Schema::dropIfExists('trip_post_photos');
        Schema::dropIfExists('trip_posts');
    }
};
