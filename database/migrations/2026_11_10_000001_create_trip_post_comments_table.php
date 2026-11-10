<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration {
    public function up(): void
    {
        // Comments on photo posts; everyone on the trip can read and add them.
        Schema::create('trip_post_comments', function (Blueprint $t) {
            $t->id();
            $t->foreignId('trip_post_id')->constrained();
            $t->foreignId('trip_member_id')->constrained();
            $t->text('body');
            $t->timestamps();
            $t->index(['trip_post_id', 'id']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('trip_post_comments');
    }
};
