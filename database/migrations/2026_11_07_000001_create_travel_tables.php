<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('trips', function (Blueprint $t) {
            $t->id(); $t->foreignId('organizer_user_id')->constrained('users')->restrictOnDelete();
            $t->string('name'); $t->text('description')->nullable(); $t->string('destination');
            $t->date('starts_on'); $t->date('ends_on'); $t->string('timezone')->default('Asia/Manila');
            $t->char('currency_code', 3)->default('PHP'); $t->string('status')->default('planning'); $t->timestamps();
        });
        Schema::create('trip_members', function (Blueprint $t) {
            $t->id(); $t->foreignId('trip_id')->constrained()->restrictOnDelete(); $t->foreignId('user_id')->constrained()->restrictOnDelete();
            $t->string('status')->default('active'); $t->timestamp('joined_at')->nullable(); $t->timestamp('left_at')->nullable();
            $t->timestamps(); $t->unique(['trip_id', 'user_id']);
        });
        Schema::create('trip_invitations', function (Blueprint $t) {
            $t->id(); $t->foreignId('trip_id')->constrained(); $t->foreignId('created_by_user_id')->constrained('users');
            $t->string('token_hash', 64)->unique(); $t->timestamp('expires_at'); $t->unsignedInteger('max_uses')->default(50);
            $t->unsignedInteger('uses_count')->default(0); $t->timestamp('revoked_at')->nullable(); $t->timestamps();
        });
        Schema::create('itinerary_items', function (Blueprint $t) {
            $t->id(); $t->foreignId('trip_id')->constrained(); $t->foreignId('created_by_user_id')->constrained('users');
            $t->string('title'); $t->text('description')->nullable(); $t->string('location')->nullable();
            $t->dateTime('starts_at'); $t->dateTime('ends_at'); $t->string('timezone'); $t->unsignedInteger('sort_order')->default(0); $t->timestamps();
        });
        Schema::create('itinerary_participants', function (Blueprint $t) {
            $t->id(); $t->foreignId('itinerary_item_id')->constrained(); $t->foreignId('trip_member_id')->constrained();
            $t->string('status')->default('going'); $t->timestamps(); $t->unique(['itinerary_item_id', 'trip_member_id']);
        });
        Schema::create('expense_categories', function (Blueprint $t) {
            $t->id(); $t->foreignId('trip_id')->nullable()->constrained(); $t->string('name'); $t->string('icon')->nullable();
            $t->unsignedInteger('sort_order')->default(0); $t->boolean('is_active')->default(true); $t->timestamps();
        });
        Schema::create('expenses', function (Blueprint $t) {
            $t->id(); $t->foreignId('trip_id')->constrained()->restrictOnDelete(); $t->foreignId('created_by_member_id')->constrained('trip_members');
            $t->foreignId('category_id')->constrained('expense_categories'); $t->foreignId('itinerary_item_id')->nullable()->constrained();
            $t->string('title'); $t->string('merchant')->nullable(); $t->date('incurred_at'); $t->char('currency_code', 3);
            $t->unsignedBigInteger('total_minor'); $t->string('split_method'); $t->string('status')->default('draft');
            $t->unsignedInteger('version')->default(1); $t->timestamp('completed_at')->nullable(); $t->timestamp('closed_at')->nullable();
            $t->timestamp('cancelled_at')->nullable(); $t->text('cancellation_reason')->nullable(); $t->timestamps(); $t->index(['trip_id', 'status']);
        });
        Schema::create('expense_payments', function (Blueprint $t) {
            $t->id(); $t->foreignId('expense_id')->constrained(); $t->foreignId('trip_member_id')->constrained();
            $t->unsignedBigInteger('amount_minor'); $t->timestamp('paid_at')->nullable(); $t->timestamps();
        });
        Schema::create('expense_items', function (Blueprint $t) {
            $t->id(); $t->foreignId('expense_id')->constrained(); $t->string('description'); $t->decimal('quantity', 12, 3)->default(1);
            $t->unsignedBigInteger('unit_price_minor'); $t->unsignedBigInteger('line_total_minor');
            $t->foreignId('category_id')->nullable()->constrained('expense_categories'); $t->unsignedInteger('sort_order')->default(0); $t->timestamps();
        });
        Schema::create('expense_adjustments', function (Blueprint $t) {
            $t->id(); $t->foreignId('expense_id')->constrained(); $t->foreignId('expense_item_id')->nullable()->constrained();
            $t->string('type'); $t->bigInteger('amount_minor'); $t->string('allocation_method'); $t->timestamps();
        });
        Schema::create('expense_allocations', function (Blueprint $t) {
            $t->id(); $t->foreignId('expense_id')->constrained(); $t->foreignId('expense_item_id')->nullable()->constrained();
            $t->foreignId('expense_adjustment_id')->nullable()->constrained(); $t->foreignId('trip_member_id')->constrained();
            $t->bigInteger('amount_minor'); $t->decimal('basis_value', 15, 4)->nullable(); $t->timestamps();
        });
        Schema::create('expense_allocation_proposals', function (Blueprint $t) {
            $t->id(); $t->foreignId('expense_id')->constrained(); $t->foreignId('proposed_by_member_id')->constrained('trip_members');
            $t->unsignedInteger('base_version'); $t->json('proposed_allocations'); $t->string('status')->default('pending');
            $t->foreignId('reviewed_by_member_id')->nullable()->constrained('trip_members'); $t->timestamp('reviewed_at')->nullable(); $t->timestamps();
        });
        Schema::create('receipts', function (Blueprint $t) {
            $t->id(); $t->foreignId('expense_id')->constrained(); $t->foreignId('uploaded_by_member_id')->constrained('trip_members');
            $t->string('storage_path'); $t->string('mime_type'); $t->string('file_hash', 64); $t->string('processing_status')->default('uploaded'); $t->timestamps();
        });
        Schema::create('receipt_extractions', function (Blueprint $t) {
            $t->id(); $t->foreignId('receipt_id')->constrained(); $t->string('provider'); $t->string('status')->default('pending');
            $t->json('raw_result')->nullable(); $t->json('normalized_result')->nullable(); $t->decimal('confidence', 5, 4)->nullable();
            $t->text('error_message')->nullable(); $t->foreignId('reviewed_by_member_id')->nullable()->constrained('trip_members');
            $t->timestamp('reviewed_at')->nullable(); $t->timestamps();
        });
        Schema::create('settlements', function (Blueprint $t) {
            $t->id(); $t->foreignId('trip_id')->constrained(); $t->foreignId('from_member_id')->constrained('trip_members');
            $t->foreignId('to_member_id')->constrained('trip_members'); $t->unsignedBigInteger('amount_minor'); $t->char('currency_code', 3);
            $t->string('payment_method'); $t->string('status')->default('pending'); $t->timestamp('paid_at'); $t->timestamp('confirmed_at')->nullable(); $t->timestamps();
        });
        Schema::create('activity_logs', function (Blueprint $t) {
            $t->id(); $t->foreignId('trip_id')->constrained(); $t->foreignId('actor_user_id')->constrained('users');
            $t->string('subject_type'); $t->unsignedBigInteger('subject_id'); $t->string('action');
            $t->json('before')->nullable(); $t->json('after')->nullable(); $t->timestamps();
        });
    }

    public function down(): void
    {
        foreach (['activity_logs', 'settlements', 'receipt_extractions', 'receipts', 'expense_allocation_proposals', 'expense_allocations', 'expense_adjustments', 'expense_items', 'expense_payments', 'expenses', 'expense_categories', 'itinerary_participants', 'itinerary_items', 'trip_invitations', 'trip_members', 'trips'] as $table) {
            Schema::dropIfExists($table);
        }
    }
};
