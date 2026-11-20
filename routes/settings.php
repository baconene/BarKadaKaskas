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

    Route::get('settings/profile', [ProfileController::class, 'edit'])->name('profile.edit');
    Route::patch('settings/profile', [ProfileController::class, 'update'])->name('profile.update');
    Route::delete('settings/profile', [ProfileController::class, 'destroy'])->name('profile.destroy');

    Route::get('settings/password', [PasswordController::class, 'edit'])->name('password.edit');
    Route::put('settings/password', [PasswordController::class, 'update'])->name('password.update');

    // The travel app has a single light theme; the old appearance page now leads to the profile.
    Route::redirect('settings/appearance', '/settings/profile')->name('appearance');
});
