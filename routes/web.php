<?php

use App\Http\Controllers\{ExpenseController, SettlementController, TripController};
use Illuminate\Support\Facades\Route;
use Inertia\Inertia;

Route::get('/', function () {
    return Inertia::render('Landing');
})->name('home');

Route::get('join/{token}', [TripController::class, 'invitation'])->middleware('throttle:60,1')->name('invitations.show');

Route::middleware('auth')->group(function () {
    Route::get('dashboard', [TripController::class, 'index'])->name('dashboard');
    Route::post('trips', [TripController::class, 'store'])->name('trips.store');
    Route::get('trips/{trip}', [TripController::class, 'show'])->name('trips.show');
    Route::post('trips/{trip}/invitations', [TripController::class, 'invite'])->middleware('throttle:10,1')->name('trips.invite');
    Route::post('join/{token}', [TripController::class, 'join'])->middleware('throttle:10,1')->name('invitations.join');
    Route::post('trips/{trip}/itinerary', [TripController::class, 'itinerary'])->name('itinerary.store');
    Route::post('trips/{trip}/expenses', [ExpenseController::class, 'store'])->name('expenses.store');
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
