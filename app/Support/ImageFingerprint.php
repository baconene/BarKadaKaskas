<?php

namespace App\Support;

use Illuminate\Support\Facades\{Cache, Storage};

/**
 * A small fingerprint of a photo, used to spot near-duplicates (the same scenery shot twice, burst shots, re-uploads):
 * a 64-bit difference hash (the picture's light/dark structure) plus a 3×3 grid of average colours. Fingerprints are
 * cached per stored file, since stored photos never change.
 */
class ImageFingerprint
{
    /** @return array{hash: string, colors: list<int>}|null null when the file can't be read as an image */
    public static function of(string $path): ?array
    {
        return Cache::rememberForever('img-fp:v1:'.$path, function () use ($path) {
            $bytes = Storage::disk('local')->get($path);
            return $bytes ? self::fromBytes($bytes) : null;
        });
    }

    /** @return array{hash: string, colors: list<int>}|null */
    public static function fromBytes(string $bytes): ?array
    {
        if (! function_exists('imagecreatefromstring')) { return null; }
        $image = @imagecreatefromstring($bytes);
        if (! $image) { return null; }
        $w = imagesx($image); $h = imagesy($image);

        // Difference hash: 9×8 greyscale, each pixel compared with its right neighbour.
        $small = imagecreatetruecolor(9, 8);
        imagecopyresampled($small, $image, 0, 0, 0, 0, 9, 8, $w, $h);
        $hash = '';
        for ($y = 0; $y < 8; $y++) {
            for ($x = 0; $x < 8; $x++) { $hash .= self::grey($small, $x, $y) > self::grey($small, $x + 1, $y) ? '1' : '0'; }
        }
        // Average colour of each cell of a 3×3 grid.
        $grid = imagecreatetruecolor(3, 3);
        imagecopyresampled($grid, $image, 0, 0, 0, 0, 3, 3, $w, $h);
        $colors = [];
        for ($y = 0; $y < 3; $y++) {
            for ($x = 0; $x < 3; $x++) { $rgb = imagecolorat($grid, $x, $y); array_push($colors, ($rgb >> 16) & 255, ($rgb >> 8) & 255, $rgb & 255); }
        }
        imagedestroy($small); imagedestroy($grid); imagedestroy($image);
        return ['hash' => $hash, 'colors' => $colors];
    }

    /** Whether two photos look like the same shot: nearly the same structure, or similar structure and colours. */
    public static function similar(?array $a, ?array $b): bool
    {
        if (! $a || ! $b) { return false; }
        $distance = 0;
        for ($i = 0; $i < 64; $i++) { $distance += $a['hash'][$i] !== $b['hash'][$i] ? 1 : 0; }
        $colorGap = array_sum(array_map(fn ($x, $y) => abs($x - $y), $a['colors'], $b['colors'])) / count($a['colors']);
        return $distance <= 6 || ($distance <= 14 && $colorGap < 20) || ($distance <= 20 && $colorGap < 8);
    }

    private static function grey($image, int $x, int $y): float
    {
        $rgb = imagecolorat($image, $x, $y);
        return 0.299 * (($rgb >> 16) & 255) + 0.587 * (($rgb >> 8) & 255) + 0.114 * ($rgb & 255);
    }
}
