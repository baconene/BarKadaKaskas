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
        'golden-hour' => 'a warm golden-hour photograph with soft sunset light and gentle glow',
        'postcard' => 'a vintage travel postcard with faded film colours and a nostalgic feel',
    ];

    /**
     * Ready-made edits for trip photos: they change one thing and keep the photo real (unlike STYLES, which redraw it).
     * Each value is the instruction given to the model.
     */
    public const EDITS = [
        'enhance' => 'Enhance the photo: sharper detail, balanced exposure, natural vivid colours, less noise. Make it look like it was taken with a better camera.',
        'brighten' => 'Fix the lighting: brighten dark areas and faces, recover shadows and keep highlights natural.',
        'blue-sky' => 'Replace a dull, grey or washed-out sky with a clear blue sky with a few soft white clouds, matching the scene’s lighting.',
        'sunset-sky' => 'Turn the sky into a warm, colourful sunset and add matching warm light on the scene.',
        'remove-people' => 'Remove strangers and passers-by in the background, filling the space naturally. Keep the main people in the photo exactly as they are.',
        'tropical' => 'Make the colours feel like a sunny tropical day: bluer water, greener plants, warm sunlight, without looking fake.',
    ];

    /** Styles offered for each kind of photo.
    public const SUBJECT_STYLES = [
        'portrait' => ['cartoon', 'anime', 'watercolor', '3d', 'pop-art', 'sketch', 'headshot', 'beach'],
        'photo' => ['cartoon', 'anime', 'watercolor', '3d', 'pop-art', 'sketch', 'golden-hour', 'postcard', 'enhance', 'brighten', 'blue-sky', 'sunset-sky', 'remove-people', 'tropical'],
    ];

    public function __construct(private readonly VertexClient $vertex) {}

    public function model(): string
    {
        return (string) (config('services.gemini.image_model') ?: 'gemini-2.5-flash-image');
    }

    /** @return array{mime: string, data: string} base64 image */
    /** [subject] "portrait" keeps the person recognisable for a profile picture; "photo" restyles a trip photo or cover. */
    public function restyle(string $bytes, string $mimeType, ?string $style, ?string $custom, string $subject = 'portrait'): array
    {
        $look = $custom ? trim($custom) : (self::STYLES[$style] ?? self::STYLES['cartoon']);
        $edit = $subject === 'photo' && ($custom || isset(self::EDITS[$style]));
        $prompt = $edit
            // Edits (templates or the traveller's own request) change only what was asked and keep the photo realistic.
            ? 'Edit this travel photo. '.($custom ? 'Change requested: '.trim($custom).'.' : self::EDITS[$style]).' Keep everything else the same: people (faces, bodies, clothes), landmarks, composition and aspect ratio. The result must look like a real photograph. No text, captions, borders or watermarks.'
            : ($subject === 'photo'
            ? "Restyle this travel photo as {$look}. Keep the same scene, people, landmarks and composition, at the same aspect ratio. Keep any people recognisable. No text, captions, borders or watermarks."
            : "Restyle this profile photo as {$look}. Keep the same person clearly recognisable: same face, hair, skin tone, expression and pose. "
                .'Make it a square, centred head-and-shoulders portrait that works as a small round profile picture. No text, captions, borders or watermarks.');
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
