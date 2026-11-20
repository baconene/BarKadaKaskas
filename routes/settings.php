<?php

use App\Http\Controllers\Settings\PasswordController;
use App\Http\Controllers\Settings\ProfileController;
use Illuminate\Support\Facades\Route;
use Inertia\Inertia;

Route::middleware('auth')->group(function () {
    Route::redirect('settings', 'settings/profile');

    // Premium (AI features), paid through PayMongo.
    Route::get('settings/premium', [\App\Http\Controllers\PremiumController::class, 'show'])->name('premium.show');
    Route::post('premium/checkout', [\App\Http\Controllers\PremiumController::class, 'checkout'])->middleware('throttle:10,1')->name('premium.checkout');
    Route::get('premium/return/{payment}', [\App\Http\Controllers\PremiumController::class, 'back'])->name('premium.return');
    // Subscription with the free trial: save a card (checked with a released hold), then monthly charges until cancelled.
    Route::post('premium/subscribe', [\App\Http\Controllers\SubscriptionController::class, 'start'])->middleware('throttle:6,1')->name('premium.subscribe');
    Route::post('premium/subscribe/{payment}/card', [\App\Http\Controllers\SubscriptionController::class, 'card'])->middleware('throttle:10,1')->name('premium.subscribe.card');
    Route::get('premium/subscribe/{payment}/return', [\App\Http\Controllers\SubscriptionController::class, 'back'])->name('premium.subscribe.return');
    Route::get('premium/renewal/{payment}/return', [\App\Http\Controllers\SubscriptionController::class, 'renewalBack'])->name('premium.renewal.return');
    Route::post('premium/cancel', [\App\Http\Controllers\SubscriptionController::class, 'cancel'])->middleware('throttle:10,1')->name('premium.cancel');

    Route::get('settings/profile', [ProfileController::class, 'edit'])->name('profile.edit');
    Route::patch('settings/profile', [ProfileController::class, 'update'])->name('profile.update');
    Route::delete('settings/profile', [ProfileController::class, 'destroy'])->name('profile.destroy');

    Route::get('settings/password', [PasswordController::class, 'edit'])->name('password.edit');
    Route::put('settings/password', [PasswordController::class, 'update'])->name('password.update');

    // The travel app has a single light theme; the old appearance page now leads to the profile.
    Route::redirect('settings/appearance', '/settings/profile')->name('appearance');
});
