<?php

namespace App\Http\Middleware;

use Closure;
use Illuminate\Http\Request;

/**
 * The app answers "the AI service failed" (502) and "the AI service was too slow" (504) with a JSON message, but
 * proxies in front of the server (Forge's edge, Cloudflare) treat 502/504 as their own gateway errors and replace the
 * body, so people only saw "HTTP 502". Those answers go out as 503 instead, which proxies pass through untouched.
 */
class KeepErrorMessages
{
    public function handle(Request $request, Closure $next)
    {
        $response = $next($request);
        if (in_array($response->getStatusCode(), [502, 504], true) && str_contains((string) $response->headers->get('Content-Type'), 'json')) {
            $response->setStatusCode(503);
        }
        return $response;
    }
}
