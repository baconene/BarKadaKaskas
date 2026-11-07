<?php

use App\Http\Controllers\{ExpenseController, SettlementController, TripController, TripCoverController};
use App\Http\Controllers\Api\AuthController;
use Illuminate\Support\Facades\Route;

Route::prefix('v1')->name('api.v1.')->group(function () {
    Route::post('auth/register', [AuthController::class, 'register'])->middleware('throttle:5,1');
    Route::post('auth/login', [AuthController::class, 'login'])->middleware('throttle:10,1');
    Route::get('invitations/{token}', [TripController::class, 'invitation'])->middleware('throttle:60,1');

    Route::middleware(['auth:sanctum', 'throttle:120,1'])->group(function () {
        Route::get('auth/me', [AuthController::class, 'me']);
        Route::post('auth/logout', [AuthController::class, 'logout']);
        Route::get('trips', [TripController::class, 'index']);
        Route::post('trips', [TripController::class, 'store']);
        Route::get('trips/{trip}', [TripController::class, 'show']);
        Route::patch('trips/{trip}', [TripController::class, 'update']);
        Route::post('trips/{trip}/cover', [TripCoverController::class, 'store'])->middleware('throttle:20,1');
        Route::delete('trips/{trip}/cover', [TripCoverController::class, 'destroy']);
        Route::post('trips/{trip}/cancel', [TripController::class, 'cancel']);
        Route::post('trips/{trip}/reopen', [TripController::class, 'reopen']);
        Route::patch('trips/{trip}/visibility', [TripController::class, 'visibility']);
        Route::post('trips/{trip}/invitations', [TripController::class, 'invite'])->middleware('throttle:10,1');
        Route::post('invitations/{token}/join', [TripController::class, 'join']);
        Route::post('trips/{trip}/itinerary', [TripController::class, 'itinerary']);
        Route::patch('trips/{trip}/itinerary/{item}', [TripController::class, 'updateItinerary']);
        Route::post('trips/{trip}/expenses', [ExpenseController::class, 'store']);
        Route::patch('trips/{trip}/expenses/{expense}', [ExpenseController::class, 'update']);
        Route::patch('trips/{trip}/expenses/{expense}/status', [ExpenseController::class, 'status']);
        Route::post('trips/{trip}/expenses/{expense}/payments', [ExpenseController::class, 'payment']);
        Route::post('trips/{trip}/expenses/{expense}/shares/paid', [ExpenseController::class, 'sharePaid']);
        Route::post('trips/{trip}/expenses/{expense}/receipts', [ExpenseController::class, 'receipt']);
        Route::get('receipts/{receipt}', [ExpenseController::class, 'download']);
        Route::post('trips/{trip}/settlements', [SettlementController::class, 'store']);
        Route::post('trips/{trip}/settlements/{settlement}/confirm', [SettlementController::class, 'confirm']);
    });
});
