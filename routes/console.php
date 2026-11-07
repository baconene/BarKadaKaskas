<?php

use Illuminate\Foundation\Inspiring;
use Illuminate\Support\Facades\Artisan;

Artisan::command('inspire', function () {
    $this->comment(Inspiring::quote());
})->purpose('Display an inspiring quote');

// Deletes receipt photos that were scanned but never saved as an expense (needs the scheduler: `php artisan schedule:run` every minute).
Artisan::command('receipts:prune-scans', function () {
    $this->info('Removed '.\App\Http\Controllers\ReceiptScanController::prune().' unused receipt scans.');
})->purpose('Delete receipt scans that were never saved as an expense')->daily();
