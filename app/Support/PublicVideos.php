<?php

namespace App\Support;

use Illuminate\Http\UploadedFile;
use Illuminate\Support\Facades\Storage;
use Illuminate\Support\Str;

/**
 * Videos posted to a trip, stored like trip photos (random 40-character name, served without a session so the apps
 * can play them). Served with byte-range support, so phones and browsers can stream and seek.
 */
class PublicVideos
{
    public static function store(UploadedFile $file): string
    {
        $extension = match ($file->getMimeType()) { 'video/quicktime' => 'mov', 'video/webm' => 'webm', 'video/3gpp' => '3gp', default => 'mp4' };
        return $file->storeAs('trip-videos', Str::random(40).'.'.$extension, 'local');
    }

    public static function url(?string $path): ?string
    {
        return $path ? route('trip-videos.show', basename($path)) : null;
    }

    public static function serve(string $file)
    {
        abort_unless(preg_match('/^[A-Za-z0-9]{40}\.(mp4|mov|webm|3gp)$/', $file) === 1, 404);
        $path = 'trip-videos/'.$file;
        abort_unless(Storage::disk('local')->exists($path), 404);
        $type = match (pathinfo($file, PATHINFO_EXTENSION)) { 'mov' => 'video/quicktime', 'webm' => 'video/webm', '3gp' => 'video/3gpp', default => 'video/mp4' };
        // BinaryFileResponse answers Range requests (206), which video players need to stream and seek.
        return response()->file(Storage::disk('local')->path($path), ['Content-Type' => $type, 'Cache-Control' => 'public, max-age=31536000, immutable', 'X-Content-Type-Options' => 'nosniff']);
    }
}
