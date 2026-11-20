<?php

return [
    'google_maps' => [
        'browser_key' => env('GOOGLE_MAPS_BROWSER_KEY', ''),
        'map_id' => env('GOOGLE_MAPS_MAP_ID', 'DEMO_MAP_ID'),
    ],

    // Receipt scanning with Gemini on Vertex AI (see docs/receipt-scanning.md). Use either a service-account
    // key (VERTEX_CREDENTIALS: JSON, base64 JSON or a file path) or a Vertex express-mode VERTEX_API_KEY.
    'gemini' => [
        'credentials' => env('VERTEX_CREDENTIALS'),
        'api_key' => env('VERTEX_API_KEY'),
        'project' => env('VERTEX_PROJECT_ID'),
        'location' => env('VERTEX_LOCATION') ?: 'global',
        'model' => env('VERTEX_GEMINI_MODEL') ?: 'gemini-2.5-flash',
        // Image model for AI profile-photo styles (a Gemini "Nano Banana" image model from Model Garden).
        'image_model' => env('VERTEX_IMAGE_MODEL') ?: 'gemini-2.5-flash-image',
    ],

    // The landing page's "Download for Android" button. GitHub serves the newest release's APK at this
    // permanent URL (published by .github/workflows/mobile.yml). An empty env value keeps the default.
    'android' => [
        'download_url' => env('ANDROID_DOWNLOAD_URL') ?: 'https://github.com/baconene/BarKadaKaskas/releases/latest/download/BarkadaKasKas.apk',
        'ios_download_url' => env('IOS_DOWNLOAD_URL') ?: 'https://github.com/baconene/BarKadaKaskas/releases/latest/download/BarkadaKasKas.ipa',
        'releases_url' => env('ANDROID_RELEASES_URL') ?: 'https://github.com/baconene/BarKadaKaskas/releases',
        // In-app updates: where the releases come from, and the oldest build still allowed without updating.
        'repository' => env('ANDROID_RELEASES_REPO') ?: 'baconene/BarKadaKaskas',
        'min_version' => (int) env('ANDROID_MIN_VERSION', 0),
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

    // Who runs the service and how to reach them, shown on the Terms, Subscription Terms and Privacy Policy pages.
    'legal' => [
        'operator' => env('LEGAL_OPERATOR_NAME', 'BarkadaKasKas'),
        'contact_email' => env('LEGAL_CONTACT_EMAIL'),
        'effective' => env('LEGAL_EFFECTIVE_DATE', 'November 21, 2026'),
    ],

    // Premium (AI features) is paid monthly through PayMongo Checkout (cards, GCash, Maya, GrabPay).
    'paymongo' => [
        'secret_key' => env('PAYMONGO_SECRET_KEY'),
        'webhook_secret' => env('PAYMONGO_WEBHOOK_SECRET'),
        // Public key (pk_...): lets the browser turn a card into a PayMongo payment method, so card numbers never
        // reach our server. Needed for the free trial / automatic monthly billing.
        'public_key' => env('PAYMONGO_PUBLIC_KEY'),
    ],
    'premium' => [
        // Price of 30 days of Premium, in centavos (PayMongo charges in pesos). Default ≈ US$25.
        'price_minor' => (int) env('PREMIUM_PRICE_MINOR', 145000),
        'currency' => 'PHP',
        // Comma-separated emails that always count as Premium (the app owner).
        'owner_emails' => env('PREMIUM_OWNER_EMAILS', ''),
        // Free trial: this many days of Premium after saving a card; the card is then charged monthly until cancelled.
        'trial_days' => (int) env('PREMIUM_TRIAL_DAYS', 1),
        // Card check when starting the trial: a temporary hold of this amount (centavos), released straight away.
        // PayMongo's minimum card amount is ₱20.
        'verify_minor' => (int) env('PREMIUM_VERIFY_MINOR', 2000),
        // Monthly allowances (calendar month, Asia/Manila). Sized so that even a subscriber who uses every one of them
        // costs less in Google fees than the subscription brings in (see docs/pricing.md). "free" is everyone else.
        'allowances' => [
            'premium' => [
                'ai_plan' => (int) env('ALLOW_PREMIUM_AI_PLAN', 30),        // AI trip planner requests
                'receipt_scan' => (int) env('ALLOW_PREMIUM_RECEIPTS', 60),  // AI receipt reads
                'ai_image' => (int) env('ALLOW_PREMIUM_AI_IMAGE', 20),      // AI photo restyles and highlight covers
                'ai_insight' => (int) env('ALLOW_PREMIUM_AI_INSIGHT', 150), // new tips, day prep and budget estimates
                'navigation' => (int) env('ALLOW_PREMIUM_NAVIGATION', 100), // in-app turn-by-turn routes
                'place_photos' => (int) env('ALLOW_PREMIUM_PLACE_PHOTOS', 100), // new stops looked up for Google photos
            ],
            'trial' => [
                'ai_plan' => 5, 'receipt_scan' => 5, 'ai_image' => 3, 'ai_insight' => 10, 'navigation' => 10, 'place_photos' => 10,
            ],
            'free' => [
                'ai_plan' => 0, 'receipt_scan' => 0, 'ai_image' => 0, 'ai_insight' => 0,
                'navigation' => (int) env('ALLOW_FREE_NAVIGATION', 5),
                'place_photos' => (int) env('ALLOW_FREE_PLACE_PHOTOS', 10),
            ],
        ],
    ],
];
