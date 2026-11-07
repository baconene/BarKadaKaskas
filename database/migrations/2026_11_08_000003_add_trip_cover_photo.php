<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration {
    public function up(): void
    {
        // Path on the private "local" disk; served by an unguessable file name (see TripCoverController).
        Schema::table('trips', fn (Blueprint $table) => $table->string('cover_path')->nullable());
    }

    public function down(): void
    {
        Schema::table('trips', fn (Blueprint $table) => $table->dropColumn('cover_path'));
    }
};
