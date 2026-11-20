<?php

namespace App\Http\Controllers;

use App\Models\{PremiumPayment, User};
use App\Support\Premium;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\{DB, Http, Log};
use Inertia\Inertia;

/**
 * Premium: 30 days of AI features per payment, paid through PayMongo Checkout (cards, GCash, Maya, GrabPay). A
 * payment counts once PayMongo says it's paid: through the webhook, or when the payer comes back from checkout
 * (the checkout session is looked up then, so it works even before the webhook is set up). Paying again while
 * Premium adds 30 days to the end.
 */
class PremiumController extends Controller
{
    private const API = 'https://api.paymongo.com/v1';

    public function show(Request $request)
    {
        $status = Premium::status($request->user());
        $payments = $request->user()->hasMany(PremiumPayment::class)->where('status', 'paid')->latest('paid_at')->take(12)->get(['id', 'amount_minor', 'currency_code', 'paid_at', 'period_end']);
        return $request->is('api/*')
            ? response()->json(['data' => $status + ['payments' => $payments]])
            : Inertia::render('settings/Premium', ['premium' => $status, 'payments' => $payments, 'flash' => session('premium'), 'flashMessage' => session('premium_message')]);
    }

    /** Starts a PayMongo checkout for 30 days of Premium; returns where to send the payer. */
    public function checkout(Request $request)
    {
        $secret = config('services.paymongo.secret_key');
        abort_unless($secret, 503, 'Online payment isn’t set up yet. Please contact the app owner.');
        $user = $request->user();
        $payment = PremiumPayment::create(['user_id' => $user->id, 'amount_minor' => Premium::priceMinor(), 'currency_code' => Premium::currency()]);
        $back = route('premium.return', $payment);
        $response = Http::withBasicAuth($secret, '')->acceptJson()->timeout(20)->post(self::API.'/checkout_sessions', ['data' => ['attributes' => [
            'line_items' => [['name' => 'BarkadaKasKas Premium · 30 days', 'description' => 'AI trip planning and AI features for your trips', 'amount' => Premium::priceMinor(), 'currency' => Premium::currency(), 'quantity' => 1]],
            'payment_method_types' => ['card', 'gcash', 'paymaya', 'grab_pay'],
            'description' => 'BarkadaKasKas Premium (30 days)', 'reference_number' => 'premium-'.$payment->id,
            'send_email_receipt' => true, 'show_description' => true, 'show_line_items' => true,
            'billing' => ['name' => $user->name, 'email' => $user->email],
            'success_url' => $back, 'cancel_url' => $back.'?cancelled=1',
            'metadata' => ['user_id' => (string) $user->id, 'premium_payment_id' => (string) $payment->id],
        ]]]);
        if ($response->failed() || ! ($url = $response->json('data.attributes.checkout_url'))) {
            Log::warning('PayMongo checkout failed', ['status' => $response->status(), 'errors' => $response->json('errors')]);
            $payment->delete();
            abort(502, 'Couldn’t start the payment: '.($response->json('errors.0.detail') ?: 'PayMongo didn’t answer').'. Please try again.');
        }
        $payment->update(['checkout_session_id' => $response->json('data.id')]);
        return $request->is('api/*') || $request->expectsJson() ? response()->json(['data' => ['checkout_url' => $url]]) : Inertia::location($url);
    }

    /** Where PayMongo sends the payer back: confirms the payment (if paid) and shows the result. */
    public function back(Request $request, PremiumPayment $payment)
    {
        abort_unless($payment->user_id === $request->user()->id, 404);
        $paid = $request->boolean('cancelled') ? false : $this->confirm($payment);
        return to_route('premium.show')->with('premium', $paid ? 'paid' : ($request->boolean('cancelled') ? 'cancelled' : 'pending'));
    }

    /** PayMongo webhook (checkout_session.payment.paid). Verified with the webhook's signing secret. */
    public function webhook(Request $request)
    {
        $secret = (string) config('services.paymongo.webhook_secret');
        $header = (string) $request->header('Paymongo-Signature');
        parse_str(str_replace(',', '&', $header), $parts);
        $expected = hash_hmac('sha256', ($parts['t'] ?? '').'.'.$request->getContent(), $secret);
        $given = (string) (($parts['li'] ?? '') !== '' ? $parts['li'] : ($parts['te'] ?? ''));
        abort_unless($secret !== '' && $given !== '' && hash_equals($expected, $given), 401);
        if ($request->input('data.attributes.type') === 'checkout_session.payment.paid') {
            $session = $request->input('data.attributes.data');
            $payment = PremiumPayment::where('checkout_session_id', $session['id'] ?? '')->first()
                ?? PremiumPayment::find((int) ($session['attributes']['metadata']['premium_payment_id'] ?? 0));
            if ($payment) { $this->grant($payment); }
        }
        return response()->json(['ok' => true]);
    }

    /** Looks the checkout up at PayMongo and grants Premium if it was paid. */
    private function confirm(PremiumPayment $payment): bool
    {
        if ($payment->status === 'paid') { return true; }
        if (! $payment->checkout_session_id || ! ($secret = config('services.paymongo.secret_key'))) { return false; }
        $session = Http::withBasicAuth($secret, '')->acceptJson()->timeout(20)->get(self::API.'/checkout_sessions/'.$payment->checkout_session_id);
        $paid = collect($session->json('data.attributes.payments', []))->contains(fn ($p) => ($p['attributes']['status'] ?? null) === 'paid')
            || $session->json('data.attributes.payment_intent.attributes.status') === 'succeeded';
        if ($paid) { $this->grant($payment); }
        return $paid;
    }

    /** Marks the payment paid (once) and adds 30 days of Premium. */
    private function grant(PremiumPayment $payment): void
    {
        DB::transaction(function () use ($payment) {
            $payment = PremiumPayment::whereKey($payment->id)->lockForUpdate()->first();
            if (! $payment || $payment->status === 'paid') { return; }
            $user = User::whereKey($payment->user_id)->lockForUpdate()->firstOrFail();
            $from = $user->premium_until && $user->premium_until->isFuture() ? $user->premium_until : now();
            $until = $from->copy()->addDays(30);
            $user->forceFill(['premium_until' => $until])->save();
            $payment->update(['status' => 'paid', 'paid_at' => now(), 'period_end' => $until]);
        });
    }
}
