<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration {
    public function up(): void
    {
        // When "Mark as paid" applies an existing trip reimbursement to a bill, remember which one, so "Mark unpaid"
        // can give it back instead of deleting money that was really sent.
        Schema::table('settlements', fn (Blueprint $t) => $t->unsignedBigInteger('applied_from_id')->nullable()->after('expense_id'));
    }

    public function down(): void
    {
        Schema::table('settlements', fn (Blueprint $t) => $t->dropColumn('applied_from_id'));
    }
};
