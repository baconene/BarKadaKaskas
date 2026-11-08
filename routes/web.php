<?php

use App\Http\Controllers\{ExpenseController, SettlementController, ReceiptScanController, TripController, TripCoverController};
use Illuminate\Support\Facades\Route;
use Inertia\Inertia;

Route::get('/', function () {
    return Inertia::render('Landing', ['android' => ['downloadUrl' => config('services.android.download_url'), 'iosDownloadUrl' => config('services.android.ios_download_url'), 'releasesUrl' => config('services.android.releases_url')]]);
})->name('home');

// Cover photos are served by an unguessable name so the mobile apps can load them without a session.
Route::get('trip-covers/{file}', [TripCoverController::class, 'show'])->middleware('throttle:240,1')->name('trip-covers.show');

Route::get('join/{token}', [TripController::class, 'invitation'])->middleware('throttle:60,1')->name('invitations.show');

Route::middleware('auth')->group(function () {
    Route::get('dashboard', [TripController::class, 'index'])->name('dashboard');
    Route::post('trips', [TripController::class, 'store'])->name('trips.store');
    Route::get('trips/{trip}', [TripController::class, 'show'])->name('trips.show');
    Route::patch('trips/{trip}', [TripController::class, 'update'])->name('trips.update');
    Route::post('trips/{trip}/cover', [TripCoverController::class, 'store'])->middleware('throttle:20,1')->name('trips.cover.store');
    Route::delete('trips/{trip}/cover', [TripCoverController::class, 'destroy'])->name('trips.cover.destroy');
    Route::post('trips/{trip}/cancel', [TripController::class, 'cancel'])->name('trips.cancel');
    Route::post('trips/{trip}/reopen', [TripController::class, 'reopen'])->name('trips.reopen');
    Route::patch('trips/{trip}/visibility', [TripController::class, 'visibility'])->name('trips.visibility');
    Route::post('trips/{trip}/invitations', [TripController::class, 'invite'])->middleware('throttle:10,1')->name('trips.invite');
    Route::post('join/{token}', [TripController::class, 'join'])->middleware('throttle:10,1')->name('invitations.join');
    Route::post('trips/{trip}/itinerary', [TripController::class, 'itinerary'])->name('itinerary.store');
    Route::patch('trips/{trip}/itinerary/{item}', [TripController::class, 'updateItinerary'])->name('itinerary.update');
    Route::post('trips/{trip}/receipt-scans', [ReceiptScanController::class, 'store'])->middleware('throttle:15,1')->name('receipts.scan');
    Route::post('trips/{trip}/expenses', [ExpenseController::class, 'store'])->name('expenses.store');
    Route::delete('trips/{trip}/expenses/{expense}', [ExpenseController::class, 'destroy'])->name('expenses.destroy');
    Route::patch('trips/{trip}/expenses/{expense}', [ExpenseController::class, 'update'])->name('expenses.update');
    Route::post('trips/{trip}/expenses/{expense}/shares/paid', [ExpenseController::class, 'sharePaid'])->name('expenses.share-paid');
    Route::patch('trips/{trip}/expenses/{expense}/status', [ExpenseController::class, 'status'])->name('expenses.status');
    Route::post('trips/{trip}/expenses/{expense}/payments', [ExpenseController::class, 'payment'])->name('expenses.payment');
    Route::post('trips/{trip}/expenses/{expense}/receipts', [ExpenseController::class, 'receipt'])->name('receipts.store');
    Route::get('receipts/{receipt}', [ExpenseController::class, 'download'])->name('receipts.download');
    Route::post('trips/{trip}/settlements', [SettlementController::class, 'store'])->name('settlements.store');
    Route::post('trips/{trip}/settlements/{settlement}/confirm', [SettlementController::class, 'confirm'])->name('settlements.confirm');
});

require __DIR__.'/settings.php';
require __DIR__.'/auth.php';
