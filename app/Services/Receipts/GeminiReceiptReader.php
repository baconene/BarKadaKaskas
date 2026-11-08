<?php

namespace App\Services\Receipts;

use Illuminate\Support\Facades\{Cache, Http, Log};

/**
 * Reads a receipt photo with Gemini on Vertex AI and returns its JSON (amounts in major units).
 *
 * Authentication, in order of preference:
 *  - VERTEX_CREDENTIALS: a service-account key (JSON, base64 JSON, or a file path) with the
 *    "Vertex AI User" role. Exchanged for an OAuth token here, so no Google SDK is needed.
 *  - VERTEX_API_KEY: a Vertex AI express-mode API key.
 */
class GeminiReceiptReader
{
    public const PROVIDER = 'gemini-vertex';

    public function __construct(private readonly \App\Services\Vertex\VertexClient $vertex) {}

    public function configured(): bool
    {
        return $this->vertex->configured();
    }

    public function model(): string
    {
        return (string) (config('services.gemini.model') ?: 'gemini-2.5-flash');
    }

    /** @return array{raw: array, text: string} */
    public function read(string $bytes, string $mimeType, string $tripCurrency): array
    {
        if (! $this->configured()) {
            throw new ReceiptReadException('Receipt scanning is not set up yet. You can still enter the expense by hand.', 503);
        }

        $body = [
            'contents' => [['role' => 'user', 'parts' => [
                ['inlineData' => ['mimeType' => $mimeType, 'data' => base64_encode($bytes)]],
                ['text' => $this->prompt($tripCurrency)],
            ]]],
            'generationConfig' => [
                'temperature' => 0,
                'responseMimeType' => 'application/json',
                'responseSchema' => $this->schema(),
            ],
        ];

        // Reading a receipt needs no long reasoning, and "thinking" models can otherwise take over a minute,
        // which web servers cut off. Ask for minimal thinking; a model that rejects the setting is retried
        // without it, and remembered so later scans skip the failed attempt.
        $rejectsThinking = 'gemini-rejects-thinking:'.$this->model();
        $response = null;
        try {
        if (! Cache::get($rejectsThinking)) {
            $response = $this->vertex->generate($this->model(), array_merge_recursive($body, ['generationConfig' => ['thinkingConfig' => ['thinkingBudget' => 0]]]));
            if ($response->status() === 400 && str_contains(strtolower((string) $response->json('error.message')), 'think')) {
                Cache::forever($rejectsThinking, true);
                $response = null;
            }
        }
        $response ??= $this->vertex->generate($this->model(), $body);
        } catch (\App\Services\Vertex\VertexException $e) {
            throw new ReceiptReadException('Receipt scanning is misconfigured. Ask the app admin to check the Vertex AI key.', 503, $e->detail);
        }

        if ($response->failed()) {
            $detail = $response->json('error.status').': '.$response->json('error.message');
            Log::warning('Gemini receipt scan failed', ['status' => $response->status(), 'error' => $detail]);
            if ($response->status() === 404) {
                throw new ReceiptReadException('The receipt reader model "'.$this->model().'" is not available for this Google Cloud project or region. Ask the app admin to check VERTEX_GEMINI_MODEL, VERTEX_PROJECT_ID and VERTEX_LOCATION.', 502, $detail);
            }
            throw new ReceiptReadException('The receipt reader is unavailable right now. Please try again, or enter the expense by hand.', 502, $detail);
        }

        $text = collect($response->json('candidates.0.content.parts', []))->pluck('text')->filter()->implode('');
        $raw = json_decode($text, true);
        if (! is_array($raw)) {
            Log::warning('Gemini receipt scan returned no JSON', ['finish' => $response->json('candidates.0.finishReason'), 'block' => $response->json('promptFeedback.blockReason')]);
            throw new ReceiptReadException('The receipt could not be read. Try a sharper, well-lit photo of the whole receipt.', 422, (string) $response->json('candidates.0.finishReason'));
        }
        if (($raw['is_receipt'] ?? true) === false) {
            throw new ReceiptReadException('That doesn’t look like a receipt. Try a photo of the whole receipt.', 422);
        }

        return ['raw' => $raw, 'text' => $text];
    }

    private function prompt(string $tripCurrency): string
    {
        return <<<PROMPT
        You are reading a photo of a purchase receipt for a group travel expense app, usually from the Philippines.
        Extract only what is printed. Never invent items or amounts; leave a field null if you cannot read it.
        - Amounts are plain numbers in major units (e.g. 1250.50), without currency symbols or thousands separators.
        - items: each purchased line with its LINE TOTAL as printed (unit price × quantity). Keep the item name as printed.
          Exclude subtotal, VAT, service charge, discount, change and payment lines.
        - service_charge: service charge added on top, if any.
        - tax_added: tax added ON TOP of item prices only. Philippine receipts are usually VAT-inclusive; then use 0.
        - discount: total discounts (including senior citizen or PWD) as a positive number.
        - total: the final amount due or paid.
        - date: the purchase date as YYYY-MM-DD.
        - currency: ISO 4217 code. ₱ or "P" before amounts means PHP. If unclear, use {$tripCurrency}.
        - category: the best fit for the purchase.
        - notes: one short sentence only if something is unclear (e.g. a torn or blurry part); otherwise null.
        - is_receipt: false if the image is not a receipt or bill.
        PROMPT;
    }

    private function schema(): array
    {
        $money = ['type' => 'NUMBER', 'nullable' => true];
        return [
            'type' => 'OBJECT',
            'properties' => [
                'is_receipt' => ['type' => 'BOOLEAN'],
                'merchant' => ['type' => 'STRING', 'nullable' => true],
                'date' => ['type' => 'STRING', 'nullable' => true],
                'currency' => ['type' => 'STRING', 'nullable' => true],
                'category' => ['type' => 'STRING', 'enum' => ['food', 'transport', 'lodging', 'activities', 'shopping', 'other']],
                'items' => ['type' => 'ARRAY', 'items' => ['type' => 'OBJECT', 'properties' => [
                    'description' => ['type' => 'STRING'],
                    'quantity' => ['type' => 'NUMBER', 'nullable' => true],
                    'amount' => ['type' => 'NUMBER'],
                ], 'required' => ['description', 'amount']]],
                'service_charge' => $money, 'tax_added' => $money, 'discount' => $money, 'total' => $money,
                'notes' => ['type' => 'STRING', 'nullable' => true],
            ],
            'required' => ['is_receipt', 'items', 'total', 'category'],
        ];
    }
}
