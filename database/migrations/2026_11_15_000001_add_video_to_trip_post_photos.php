<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration {
    public function up(): void
    {
        // A post item can be a video: `path` stays its still image (poster, used by the feed grid, highlights and
        // the duplicate filter) and `video_path` is the video file.
        Schema::table('trip_post_photos', function (Blueprint $t) {
            $t->string('video_path')->nullable();
            $t->unsignedInteger('duration_ms')->nullable();
        });
    }

    public function down(): void
    {
        Schema::table('trip_post_photos', fn (Blueprint $t) => $t->dropColumn(['video_path', 'duration_ms']));
    }
};
