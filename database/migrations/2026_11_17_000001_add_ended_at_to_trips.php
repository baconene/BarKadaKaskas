<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

/** When the organizer ended the trip (status "ended"). */
return new class extends Migration
{
    public function up(): void
    {
        Schema::table('trips', function (Blueprint $table) { $table->timestamp('ended_at')->nullable()->after('cancelled_at'); });
    }

    public function down(): void
    {
        Schema::table('trips', function (Blueprint $table) { $table->dropColumn('ended_at'); });
    }
};
