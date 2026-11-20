<?php

namespace App\Http\Controllers;

use App\Support\Usage;
use Illuminate\Http\Request;

/** This month's allowances, and starting an in-app navigation (Google bills each route, so each uses one). */
class UsageController extends Controller
{
    public function show(Request $request)
    {
        return response()->json(['data' => ['plan' => Usage::plan($request->user()), 'period' => Usage::period(), 'allowances' => Usage::summary($request->user())]]);
    }

    /**
     * Called by the Android app before it asks Google's Navigation SDK for a route. When nothing is left the app
     * opens the trip in Google Maps instead, which costs nothing.
     */
    public function navigation(Request $request)
    {
        $user = $request->user();
        $allowed = Usage::take($user, 'navigation');
        $remaining = Usage::remaining($user, 'navigation');
        return response()->json(['data' => [
            'allowed' => $allowed,
            'remaining' => $remaining === PHP_INT_MAX ? null : $remaining,
            'limit' => ($limit = Usage::limit($user, 'navigation')) === PHP_INT_MAX ? null : $limit,
            'message' => $allowed ? null : Usage::message($user, 'navigation').' Opening Google Maps instead.',
        ]]);
    }
}
