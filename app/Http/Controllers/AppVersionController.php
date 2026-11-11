<?php

namespace App\Http\Controllers;

use Illuminate\Support\Facades\{Cache, Http};

/**
 * The newest Android app, for the app's "update available" check: read from the repository's latest GitHub release
 * (published by .github/workflows/mobile.yml, tag app-v0.1.<build>), cached for 10 minutes. ANDROID_MIN_VERSION
 * makes every older build require the update.
 */
class AppVersionController extends Controller
{
    public function android()
    {
        $release = Cache::remember('app-version:android', 600, function () {
            $repo = config('services.android.repository');
            $response = Http::connectTimeout(5)->timeout(10)->acceptJson()->withUserAgent('BarkadaKasKas')
                ->get("https://api.github.com/repos/{$repo}/releases/latest");
            if (! $response->ok()) { return null; }
            $data = $response->json();
            if (! preg_match('/^app-v(\d+\.\d+)\.(\d+)$/', (string) ($data['tag_name'] ?? ''), $m)) { return null; }
            $apk = collect($data['assets'] ?? [])->firstWhere('name', 'BarkadaKasKas.apk');
            if (! $apk) { return null; }
            return [
                'version_code' => (int) $m[2],
                'version_name' => $m[1].'.'.$m[2],
                'apk_url' => $apk['browser_download_url'],
                'size' => (int) ($apk['size'] ?? 0),
                'notes' => self::notes((string) ($data['body'] ?? '')),
                'published_at' => $data['published_at'] ?? null,
            ];
        });
        abort_unless($release, 503, 'Could not check for updates right now.');
        return response()->json(['data' => $release + ['min_version_code' => (int) config('services.android.min_version')]]);
    }

    /** The release notes without commit trailers (co-author lines and the like). */
    private static function notes(string $body): string
    {
        $lines = array_filter(preg_split('/\R/', $body), fn ($line) => ! preg_match('/^[A-Za-z-]+: \S/', trim($line)));
        return trim(mb_substr(trim(implode("\n", $lines)), 0, 2000));
    }
}
