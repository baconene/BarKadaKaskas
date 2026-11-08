<?php

namespace App\Support;

use Illuminate\Http\UploadedFile;
use Illuminate\Support\Facades\Storage;
use Illuminate\Support\Str;

/**
 * Images (trip covers, profile pictures, trip photos) stored on the private disk under a random
 * 40-character name and served by that name without a session, so the mobile apps can load them as
 * plain image URLs. The name changes on every upload, so responses can be cached for a long time.
 */
class PublicImages
{
    public const DIRECTORIES = ['trip-covers', 'avatars', 'trip-photos'];

    public static function store(UploadedFile $file, string $directory): string
    {
        $extension = match ($file->getMimeType()) { 'image/png' => 'png', 'image/webp' => 'webp', default => 'jpg' };
        return $file->storeAs($directory, Str::random(40).'.'.$extension, 'local');
    }

    public static function url(?string $path): ?string
    {
        if (! $path) { return null; }
        return route(match (dirname($path)) { 'avatars' => 'avatars.show', 'trip-photos' => 'trip-photos.show', default => 'trip-covers.show' }, basename($path));
    }

    public static function serve(string $directory, string $file)
    {
        abort_unless(in_array($directory, self::DIRECTORIES, true) && preg_match('/^[A-Za-z0-9]{40}\.(jpg|png|webp)$/', $file) === 1, 404);
        $path = $directory.'/'.$file;
        abort_unless(Storage::disk('local')->exists($path), 404);
        return Storage::disk('local')->response($path, null, ['Cache-Control' => 'public, max-age=31536000, immutable', 'X-Content-Type-Options' => 'nosniff']);
    }
}
