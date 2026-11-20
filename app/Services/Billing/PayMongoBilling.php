<?php

namespace App\Services\Billing;

use App\Models\User;
use Illuminate\Http\Client\Response;
use Illuminate\Support\Facades\{Http, Log};

/**
 * The PayMongo calls behind subscriptions: a customer per user, a saved card (a small card hold made with
 * "setup_future_usage", then released) and monthly off-session charges on that card.
 *
 * Card numbers never reach our server: the browser turns them into a PayMongo payment method with the public key
 * (PAYMONGO_PUBLIC_KEY) and sends us only its id.
 *
 * VERIFY IN PAYMONGO TEST MODE before going live: saving cards for off-session charges has to be enabled on the
 * PayMongo account, and the attribute names below follow PayMongo's Payment Intent / Customers API as documented
 * when this was written. All of it is in this one class, so any difference is fixed here.
 */
class PayMongoBilling
{
    private const API = 'https://api.paymongo.com/v1';

    public function configured(): bool
    {
        return (bool) config('services.paymongo.secret_key') && (bool) config('services.paymongo.public_key');
    }

    /** The user's PayMongo customer id, created on first use. */
    public function customer(User $user): string
    {
        if ($user->paymongo_customer_id) { return $user->paymongo_customer_id; }
        [$first, $last] = array_pad(explode(' ', trim($user->name), 2), 2, '');
        $response = $this->post('/customers', ['first_name' => $first ?: $user->name, 'last_name' => $last ?: '-', 'email' => $user->email, 'default_device' => 'email']
            + ($user->phone ? ['phone' => $user->phone] : []));
        $id = (string) $response->json('data.id');
        $user->forceFill(['paymongo_customer_id' => $id])->save();
        return $id;
    }

    /**
     * A payment intent that checks the card with a temporary hold of [amount] (captured manually, so it can be
     * released) and saves the card on the customer for later charges. @return array{id: string, client_key: ?string}
     */
    public function cardSetupIntent(User $user, int $amount, string $description): array
    {
        $response = $this->post('/payment_intents', [
            'amount' => $amount, 'currency' => 'PHP', 'description' => $description,
            'payment_method_allowed' => ['card'],
            'payment_method_options' => ['card' => ['request_three_d_secure' => 'any']],
            'capture_type' => 'manual',
            'setup_future_usage' => ['session_type' => 'off_session', 'customer_id' => $this->customer($user)],
            'statement_descriptor' => 'BarkadaKasKas',
            'metadata' => ['user_id' => (string) $user->id, 'purpose' => 'card_check'],
        ]);
        return ['id' => (string) $response->json('data.id'), 'client_key' => $response->json('data.attributes.client_key')];
    }

    /**
     * Attaches a payment method to an intent. @return array{status: string, redirect: ?string, error: ?string}
     * status is PayMongo's: awaiting_next_action (3-D Secure: send the payer to [redirect]), awaiting_capture
     * (hold placed), succeeded, processing or awaiting_payment_method (declined; [error] says why).
     */
    public function attach(string $intentId, string $paymentMethodId, string $returnUrl, ?string $customerId = null): array
    {
        $response = $this->post('/payment_intents/'.$intentId.'/attach', ['payment_method' => $paymentMethodId, 'return_url' => $returnUrl]
            + ($customerId ? ['customer_id' => $customerId] : []), false);
        return $this->outcome($response);
    }

    /** @return array{status: string, redirect: ?string, error: ?string, payment_method: ?string, payment_id: ?string} */
    public function intent(string $intentId): array
    {
        $response = $this->request()->get(self::API.'/payment_intents/'.$intentId);
        return $this->outcome($response) + [
            'payment_method' => $response->json('data.attributes.payment_method') ?? $response->json('data.attributes.payments.0.attributes.source.id'),
            'payment_id' => $response->json('data.attributes.payments.0.id'),
        ];
    }

    /** Releases a card hold. */
    public function cancel(string $intentId): void
    {
        $response = $this->request()->post(self::API.'/payment_intents/'.$intentId.'/cancel');
        if ($response->failed()) { Log::warning('PayMongo: card hold not released', ['intent' => $intentId, 'errors' => $response->json('errors')]); }
    }

    /** @return array{brand: ?string, last4: ?string} */
    public function card(string $paymentMethodId): array
    {
        $details = $this->request()->get(self::API.'/payment_methods/'.$paymentMethodId)->json('data.attributes.details', []);
        return ['brand' => isset($details['brand']) ? mb_substr((string) $details['brand'], 0, 20) : null, 'last4' => isset($details['last4']) ? mb_substr((string) $details['last4'], -4) : null];
    }

    /**
     * Charges the user's saved card without them present (a renewal).
     * @return array{intent: ?string, status: string, redirect: ?string, error: ?string}
     */
    public function chargeSavedCard(User $user, int $amount, string $description, string $returnUrl): array
    {
        $created = $this->post('/payment_intents', [
            'amount' => $amount, 'currency' => 'PHP', 'description' => $description,
            'payment_method_allowed' => ['card'],
            'payment_method_options' => ['card' => ['request_three_d_secure' => 'automatic']],
            'capture_type' => 'automatic',
            'setup_future_usage' => ['session_type' => 'off_session', 'customer_id' => $this->customer($user)],
            'statement_descriptor' => 'BarkadaKasKas',
            'metadata' => ['user_id' => (string) $user->id, 'purpose' => 'renewal'],
        ], false);
        if ($created->failed()) { return ['intent' => null, 'status' => 'failed', 'redirect' => null, 'error' => $this->error($created)]; }
        $intent = (string) $created->json('data.id');
        return ['intent' => $intent] + $this->attach($intent, (string) $user->paymongo_payment_method_id, $returnUrl, $user->paymongo_customer_id);
    }

    private function outcome(Response $response): array
    {
        if ($response->failed()) { return ['status' => 'failed', 'redirect' => null, 'error' => $this->error($response)]; }
        $attributes = $response->json('data.attributes', []);
        return [
            'status' => (string) ($attributes['status'] ?? 'failed'),
            'redirect' => $attributes['next_action']['redirect']['url'] ?? null,
            'error' => $attributes['last_payment_error']['failed_message'] ?? $attributes['last_payment_error']['message'] ?? null,
        ];
    }

    private function error(Response $response): string
    {
        return (string) ($response->json('errors.0.detail') ?: 'PayMongo didn’t answer');
    }

    private function post(string $path, array $attributes, bool $throw = true): Response
    {
        $response = $this->request()->post(self::API.$path, ['data' => ['attributes' => $attributes]]);
        if ($throw && $response->failed()) {
            Log::warning('PayMongo request failed', ['path' => $path, 'status' => $response->status(), 'errors' => $response->json('errors')]);
            abort(502, 'Payment provider error: '.$this->error($response).'. Please try again.');
        }
        return $response;
    }

    private function request()
    {
        return Http::withBasicAuth((string) config('services.paymongo.secret_key'), '')->acceptJson()->timeout(20);
    }
}
