<?php

namespace App\Services\Vertex;

use Illuminate\Http\Client\Response;
use Illuminate\Support\Facades\{Cache, Http, Log};

/**
 * Calls Gemini models on Vertex AI (receipt reading, photo restyling). Authentication, in order of preference:
 *  - VERTEX_CREDENTIALS: a service-account key (JSON, base64 JSON, or a file path) with the "Vertex AI User" role,
 *    exchanged here for an OAuth token, so no Google SDK is needed;
 *  - VERTEX_API_KEY: an API key. With VERTEX_PROJECT_ID it targets that project and VERTEX_LOCATION (default
 *    "global"); without it Google routes express-mode requests to us-central1, where not every model exists.
 */
class VertexClient
{
    public function configured(): bool
    {
        return $this->apiKey() !== null || $this->credentials() !== null;
    }

    /** POSTs a generateContent request for [model]. Throws VertexException when misconfigured. */
    public function generate(string $model, array $body, int $timeout = 120): Response
    {
        if (! $this->configured()) { throw new VertexException('Vertex AI is not set up.'); }
        $request = Http::connectTimeout(10)->timeout($timeout)->acceptJson();
        if ($credentials = $this->credentials()) {
            $request = $request->withToken($this->accessToken($credentials));
            $url = $this->endpoint($credentials['project_id'], $model);
        } elseif ($project = config('services.gemini.project')) {
            $url = $this->endpoint((string) $project, $model).'?key='.urlencode((string) $this->apiKey());
        } else {
            $url = 'https://aiplatform.googleapis.com/v1/publishers/google/models/'.$model.':generateContent?key='.urlencode((string) $this->apiKey());
        }
        return $request->post($url, $body);
    }

    private function endpoint(string $project, string $model): string
    {
        $location = (string) (config('services.gemini.location') ?: 'global');
        $host = $location === 'global' ? 'aiplatform.googleapis.com' : "{$location}-aiplatform.googleapis.com";
        return "https://{$host}/v1/projects/".rawurlencode($project)."/locations/{$location}/publishers/google/models/{$model}:generateContent";
    }

    private function apiKey(): ?string
    {
        return config('services.gemini.api_key') ?: null;
    }

    /** @return array{client_email: string, private_key: string, project_id: string, token_uri: string}|null */
    private function credentials(): ?array
    {
        $value = trim((string) config('services.gemini.credentials'));
        if ($value === '') { return null; }
        $json = str_starts_with($value, '{') ? $value : (is_file($value) ? (string) file_get_contents($value) : (string) base64_decode($value, true));
        $key = json_decode($json, true);
        $project = config('services.gemini.project') ?: ($key['project_id'] ?? null);
        if (! is_array($key) || empty($key['client_email']) || empty($key['private_key']) || ! $project) {
            Log::warning('VERTEX_CREDENTIALS is not a usable service-account key.');
            return null;
        }
        return ['client_email' => $key['client_email'], 'private_key' => $key['private_key'], 'project_id' => $project, 'token_uri' => $key['token_uri'] ?? 'https://oauth2.googleapis.com/token'];
    }

    /** OAuth access token for the service account (JWT bearer grant), cached until shortly before it expires. */
    private function accessToken(array $credentials): string
    {
        return Cache::remember('vertex-token:'.sha1($credentials['client_email']), now()->addMinutes(50), function () use ($credentials) {
            $now = time();
            $encode = fn (array $part) => rtrim(strtr(base64_encode(json_encode($part)), '+/', '-_'), '=');
            $unsigned = $encode(['alg' => 'RS256', 'typ' => 'JWT']).'.'.$encode(['iss' => $credentials['client_email'], 'scope' => 'https://www.googleapis.com/auth/cloud-platform', 'aud' => $credentials['token_uri'], 'iat' => $now, 'exp' => $now + 3600]);
            if (! openssl_sign($unsigned, $signature, $credentials['private_key'], OPENSSL_ALGO_SHA256)) {
                throw new VertexException('The Vertex AI key could not be used.', 'openssl_sign failed');
            }
            $response = Http::asForm()->timeout(20)->post($credentials['token_uri'], ['grant_type' => 'urn:ietf:params:oauth:grant-type:jwt-bearer', 'assertion' => $unsigned.'.'.rtrim(strtr(base64_encode($signature), '+/', '-_'), '=')]);
            if ($response->failed() || ! $response->json('access_token')) {
                Log::warning('Vertex AI token exchange failed', ['status' => $response->status(), 'error' => $response->json('error_description') ?? $response->json('error')]);
                throw new VertexException('The Vertex AI key was refused.', (string) $response->json('error'));
            }
            return $response->json('access_token');
        });
    }
}
