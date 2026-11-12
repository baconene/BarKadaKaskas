<?php

namespace App\Support;

use Carbon\CarbonImmutable;

/** GPS position and capture time from a photo's EXIF data (when the server has the original file). */
class PhotoMetadata
{
    /** @return array{latitude: ?float, longitude: ?float, taken_at: ?CarbonImmutable} */
    public static function read(string $path, string $timezone): array
    {
        $none = ['latitude' => null, 'longitude' => null, 'taken_at' => null];
        if (! function_exists('exif_read_data')) { return $none; }
        $exif = @exif_read_data($path);
        if (! is_array($exif)) { return $none; }
        $lat = self::coordinate($exif['GPSLatitude'] ?? null, $exif['GPSLatitudeRef'] ?? 'N');
        $lng = self::coordinate($exif['GPSLongitude'] ?? null, $exif['GPSLongitudeRef'] ?? 'E');
        $valid = $lat !== null && $lng !== null && abs($lat) <= 90 && abs($lng) <= 180 && ! ($lat == 0.0 && $lng == 0.0);
        $taken = null;
        if (is_string($exif['DateTimeOriginal'] ?? null)) {
            // Camera clocks have no time zone; read it as the trip's local time.
            try { $taken = CarbonImmutable::createFromFormat('Y:m:d H:i:s', trim($exif['DateTimeOriginal']), $timezone) ?: null; } catch (\Throwable) { $taken = null; }
        }
        return ['latitude' => $valid ? $lat : null, 'longitude' => $valid ? $lng : null, 'taken_at' => $taken];
    }

    private static function coordinate(mixed $parts, string $ref): ?float
    {
        if (! is_array($parts) || count($parts) < 3) { return null; }
        $value = fn ($r) => is_string($r) && str_contains($r, '/') ? (($d = (float) explode('/', $r)[1]) ? (float) explode('/', $r)[0] / $d : 0.0) : (float) $r;
        $degrees = $value($parts[0]) + $value($parts[1]) / 60 + $value($parts[2]) / 3600;
        return in_array(strtoupper($ref), ['S', 'W'], true) ? -$degrees : $degrees;
    }
}
