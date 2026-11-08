<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration {
    public function up(): void
    {
        // Shown to fellow members of the traveller's trips (never to the public).
        Schema::table('users', function (Blueprint $t) {
            $t->string('phone', 40)->nullable();
            $t->string('other_contact', 120)->nullable();          // e.g. Messenger, Viber or Telegram handle
            $t->string('emergency_contact_name', 120)->nullable();
            $t->string('emergency_contact_relationship', 60)->nullable();
            $t->string('emergency_contact_phone', 40)->nullable();
            $t->string('avatar_path')->nullable();
        });
    }

    public function down(): void
    {
        Schema::table('users', fn (Blueprint $t) => $t->dropColumn(['phone', 'other_contact', 'emergency_contact_name', 'emergency_contact_relationship', 'emergency_contact_phone', 'avatar_path']));
    }
};
