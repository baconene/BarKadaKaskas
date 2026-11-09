<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration {
    public function up(): void
    {
        // A traveller's documents for a trip (boarding pass, ID, bank QR...). Files stay on the private disk and are
        // only served to trip members allowed to see them; "private" ones only to the owner and the organizer.
        Schema::create('traveler_documents', function (Blueprint $t) {
            $t->id();
            $t->foreignId('trip_id')->constrained();
            $t->foreignId('trip_member_id')->constrained();
            $t->string('type', 20);
            $t->string('label', 120)->nullable();
            $t->string('visibility', 10)->default('private');
            $t->string('path');
            $t->string('mime_type', 60);
            $t->unsignedInteger('size');
            $t->timestamps();
            $t->index(['trip_id', 'trip_member_id']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('traveler_documents');
    }
};
