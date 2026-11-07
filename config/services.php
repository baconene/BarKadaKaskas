<?php

return [
    'google_maps' => [
        'browser_key' => env('GOOGLE_MAPS_BROWSER_KEY', ''),
        'map_id' => env('GOOGLE_MAPS_MAP_ID', 'DEMO_MAP_ID'),
    ],

    // The landing page's "Download for Android" button. GitHub serves the newest release's APK at this
    // permanent URL (published by .github/workflows/mobile.yml). An empty env value keeps the default.
    'android' => [
        'download_url' => env('ANDROID_DOWNLOAD_URL') ?: 'https://github.com/baconene/BarKadaKaskas/releases/latest/download/BarkadaKasKas.apk',
        'ios_download_url' => env('IOS_DOWNLOAD_URL') ?: 'https://github.com/baconene/BarKadaKaskas/releases/latest/download/BarkadaKasKas.ipa',
        'releases_url' => env('ANDROID_RELEASES_URL') ?: 'https://github.com/baconene/BarKadaKaskas/releases',
    ],

    // Password for the organizer account created by SiquijorTripSeeder (only used when the account does not exist yet).
    'seed' => [
        'organizer_password' => env('SEED_ORGANIZER_PASSWORD') ?: 'password',
    ],

    /*
    |--------------------------------------------------------------------------
    | Third Party Services
    |--------------------------------------------------------------------------
    |
    | This file is for storing the credentials for third party services such
    | as Mailgun, Postmark, AWS and more. This file provides the de facto
    | location for this type of information, allowing packages to have
    | a conventional file to locate the various service credentials.
    |
    */

    'postmark' => [
        'token' => env('POSTMARK_TOKEN'),
    ],

    'ses' => [
        'key' => env('AWS_ACCESS_KEY_ID'),
        'secret' => env('AWS_SECRET_ACCESS_KEY'),
        'region' => env('AWS_DEFAULT_REGION', 'us-east-1'),
    ],

    'resend' => [
        'key' => env('RESEND_KEY'),
    ],

    'slack' => [
        'notifications' => [
            'bot_user_oauth_token' => env('SLACK_BOT_USER_OAUTH_TOKEN'),
            'channel' => env('SLACK_BOT_USER_DEFAULT_CHANNEL'),
        ],
    ],

];
