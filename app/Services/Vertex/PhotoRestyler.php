<?php

namespace App\Services\Vertex;

use Illuminate\Support\Facades\Log;
use RuntimeException;

/**
 * Restyles a profile photo with a Gemini image model on Vertex AI (e.g. the "Nano Banana" models).
 * Returns the new image; nothing is saved until the traveller chooses to use it.
 */
class PhotoRestyler
{
    /** Ready-made looks. Descriptions avoid naming studios or artists. */
    public const STYLES = [
        'cartoon' => 'a clean, friendly cartoon illustration with bold outlines and flat colours',
        'anime' => 'a hand-drawn anime-style illustration with soft cel shading',
        'watercolor' => 'a soft watercolour painting on textured paper',
        '3d' => 'a polished 3D animated movie character render with soft studio lighting',
        'pop-art' => 'bold pop-art with halftone dots and bright, punchy colours',
        'sketch' => 'a detailed pencil sketch with gentle shading',
        'headshot' => 'a professional studio headshot with flattering soft lighting and a plain neutral background',
        'beach' => 'a natural photo with a sunny tropical beach behind them, as if on a barkada trip',
    ];

    public function __construct(private readonly VertexClient $vertex) {}

    public function model(): string
    {
        return (string) (config('services.gemini.image_model') ?: 'gemini-2.5-flash-image');
    }

    /** @return array{mime: string, data: string} base64 image */
    public function restyle(string $bytes, string $mimeType, ?string $style, ?string $custom): array
    {
        $look = $custom ? trim($custom) : (self::STYLES[$style] ?? self::STYLES['cartoon']);
        $prompt = "Restyle this profile photo as {$look}. Keep the same person clearly recognisable: same face, hair, skin tone, expression and pose. "
            .'Make it a square, centred head-and-shoulders portrait that works as a small round profile picture. No text, captions, borders or watermarks.';
        try {
            $response = $this->vertex->generate($this->model(), [
                'contents' => [['role' => 'user', 'parts' => [
                    ['inlineData' => ['mimeType' => $mimeType, 'data' => base64_encode($bytes)]],
                    ['text' => $prompt],
                ]]],
                'generationConfig' => ['responseModalities' => ['TEXT', 'IMAGE']],
            ], 120);
        } catch (VertexException $e) {
            throw new RuntimeException('AI styles are not set up yet. Ask the app admin to add the Vertex AI key.', 503);
        }

        if ($response->failed()) {
            $detail = $response->json('error.status').': '.$response->json('error.message');
            Log::warning('Gemini photo restyle failed', ['status' => $response->status(), 'model' => $this->model(), 'error' => $detail]);
            throw new RuntimeException($response->status() === 404
                ? 'The AI image model "'.$this->model().'" is not available for this project or region. Ask the app admin to check VERTEX_IMAGE_MODEL.'
                : 'The AI couldn’t restyle the photo right now. Please try again.', 502);
        }
        foreach ($response->json('candidates.0.content.parts', []) as $part) {
            $image = $part['inlineData'] ?? $part['inline_data'] ?? null;
            if (! empty($image['data'])) {
                return ['mime' => $image['mimeType'] ?? $image['mime_type'] ?? 'image/png', 'data' => $image['data']];
            }
        }
        Log::warning('Gemini photo restyle returned no image', ['finish' => $response->json('candidates.0.finishReason'), 'block' => $response->json('promptFeedback.blockReason')]);
        throw new RuntimeException('The AI didn’t return a picture for that style. Try another style or a clearer photo of your face.', 422);
    }
}
