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

// Charges subscribers' saved cards when their free trial or month ends, and retries failed charges daily.
Artisan::command('premium:renew', function (\App\Services\Billing\SubscriptionRenewals $renewals) {
    $result = $renewals->run();
    $this->info("Renewed {$result['renewed']}, not renewed {$result['failed']}.");
})->purpose('Charge Premium subscriptions that are due')->hourly();
