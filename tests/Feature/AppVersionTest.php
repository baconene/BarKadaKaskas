<?php

namespace Tests\Feature;

use Illuminate\Support\Facades\Http;
use Tests\TestCase;

class AppVersionTest extends TestCase
{
    public function test_it_reports_the_latest_android_release(): void
    {
        config(['services.android.min_version' => 40]);
        Http::fake(['api.github.com/repos/*/releases/latest' => Http::response([
            'tag_name' => 'app-v0.1.49', 'published_at' => '2026-11-11T07:00:00Z',
            'body' => "Day highlights\n\n- Skips repeated photos\n\nCo-Authored-By: Someone <x@example.com>",
            'assets' => [['name' => 'BarkadaKasKas.ipa', 'browser_download_url' => 'https://example.com/a.ipa', 'size' => 1], ['name' => 'BarkadaKasKas.apk', 'browser_download_url' => 'https://example.com/a.apk', 'size' => 123]],
        ])]);
        $this->getJson('/api/v1/app/android')->assertOk()->assertExactJson(['data' => [
            'version_code' => 49, 'version_name' => '0.1.49', 'apk_url' => 'https://example.com/a.apk', 'size' => 123,
            'notes' => "Day highlights\n\n- Skips repeated photos", 'published_at' => '2026-11-11T07:00:00Z', 'min_version_code' => 40,
        ]]);
    }
}
