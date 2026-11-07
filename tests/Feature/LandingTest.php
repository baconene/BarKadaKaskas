<?php

namespace Tests\Feature;

use Inertia\Testing\AssertableInertia as Assert;
use Tests\TestCase;

class LandingTest extends TestCase
{
    public function test_landing_links_to_the_latest_android_release(): void
    {
        $this->withoutVite();
        $this->get('/')->assertOk()->assertInertia(fn (Assert $page) => $page->component('Landing')
            ->where('android.downloadUrl', 'https://github.com/baconene/BarKadaKaskas/releases/latest/download/BarkadaKasKas.apk')
            ->where('android.iosDownloadUrl', 'https://github.com/baconene/BarKadaKaskas/releases/latest/download/BarkadaKasKas.ipa')
            ->where('android.releasesUrl', 'https://github.com/baconene/BarKadaKaskas/releases'));
    }

    public function test_download_link_can_be_overridden(): void
    {
        $this->withoutVite();
        config(['services.android.download_url' => 'https://example.test/app.apk']);
        $this->get('/')->assertInertia(fn (Assert $page) => $page->where('android.downloadUrl', 'https://example.test/app.apk'));
    }
}
