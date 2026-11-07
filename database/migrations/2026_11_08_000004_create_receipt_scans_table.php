<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration {
    public function up(): void
    {
        // A photo scanned before its expense exists. Saving the expense attaches it as a receipt.
        Schema::create('receipt_scans', function (Blueprint $t) {
            $t->id();
            $t->foreignId('trip_id')->constrained();
            $t->foreignId('trip_member_id')->constrained();
            $t->string('storage_path'); $t->string('mime_type'); $t->string('file_hash', 64);
            $t->string('provider'); $t->string('status');            // read | failed
            $t->json('raw_result')->nullable(); $t->json('normalized_result')->nullable(); $t->text('error_message')->nullable();
            $t->foreignId('expense_id')->nullable()->constrained();   // set once used
            $t->timestamps();
        });
    }

    public function down(): void { Schema::dropIfExists('receipt_scans'); }
};
