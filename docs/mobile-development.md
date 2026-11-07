# Native mobile development

The repository contains one Laravel backend and two native clients:

| Role | Owned area |
|---|---|
| Laravel API | `routes/api.php`, API controllers, Sanctum and API tests |
| Android frontend | `android/BarKadaKaskas` Compose screens and domain interfaces |
| Android integration | Android HTTP adapter, JSON mapping and secure token storage |
| iOS frontend | `ios/BarKadaKaskas` SwiftUI screens and domain interfaces |
| iOS integration | iOS URLSession adapter, decoding and Keychain storage |

Use [the API contract](mobile-api.md) when changing either client. Money travels
as integer minor units. Mobile apps do not calculate authoritative balances;
Laravel applies the same expense rules used by the website.

## Start the local API

```sh
composer install
php artisan migrate
php artisan config:clear
php artisan serve --host=127.0.0.1 --port=8000
```

Keep this process running while testing. Android Emulator reaches this host at
`http://10.0.2.2:8000`. Enter the server origin in the app's server setting; the
network adapter adds `/api/v1`. A `.test` Herd hostname on your desktop normally
does not resolve on a phone or emulator.

For a physical device, use the HTTPS Forge deployment. A local LAN development
server requires a reachable desktop IP, an intentional LAN listener and firewall
configuration; the loopback command above does not expose the app to your LAN.
Do not put a production password into an untrusted development server.

Native login uses Laravel-issued device bearer tokens. Google Maps API keys are
not login tokens and must not be copied into native authentication settings.
The website's browser-restricted Google key is not a native Maps SDK key.

## Android

Open `android/BarKadaKaskas` in Android Studio. Use JDK 17 or 21 (Android
Studio's bundled JDK works; newer JDKs such as 25/26 are too new for this
Gradle/AGP version) and install Android SDK Platform 35. Android Studio creates
the ignored `local.properties`. The Gradle wrapper (8.11.1, checksum-pinned) is
committed.

```sh
cd android/BarKadaKaskas
./gradlew testDebugUnitTest assembleDebug
```

On Windows use `gradlew.bat`. The debug APK is in
`app/build/outputs/apk/debug/app-debug.apk`. Debug builds are for development;
store distribution requires your own signing key and a release build.

## iOS

Use a Mac with Xcode and XcodeGen:

```sh
brew install xcodegen
cd ios/BarKadaKaskas
xcodegen generate
open BarKadaKaskas.xcodeproj
```

Select the BarKadaKaskas scheme and an iOS simulator. Device builds need your
Apple development team and signing setup in Xcode. Windows cannot compile or
sign an iOS app. A simulator `.app` is not an installable iPhone IPA.

## Build automation

The **Native mobile builds** GitHub Actions workflow is manually triggered. It
builds/tests an Android debug APK and compiles the iOS simulator app on a Mac
runner without signing. It does not publish to stores or create a signed IPA.
Review the workflow result before treating a platform as verified.

## Verification checklist

- Login, register, restore a session, and logout/revoke the current device token.
- Check that changing server cannot reuse credentials from the old server.
- Create/list trips and check that another user's private trip cannot be read.
- Save a stop with transport; verify trip-local dates and location navigation.
- Create an expense, refresh authoritative shares, and confirm a repayment.
- Attempt a stale-version update, a forbidden action and an expired session.
- Interrupt a financial request: refresh before trying again, never silently
  retry a write that may already have succeeded.

## Status

| Part | State | How it was checked |
|---|---|---|
| Laravel API (`/api/v1`) | Complete for the app features below | `tests/Feature/MobileApiTest.php` (auth, privacy, splits, lifecycle, settlements, receipts, invitations, PATCH override) |
| Android UI | Login, trips, itinerary, expenses, balances, group/invite | Not compiled locally yet — run the workflow or Android Studio |
| Android integration | `LaravelRepository` (HttpURLConnection + org.json), token encrypted with an Android Keystore key | Data layer compiled against `android.jar`; `ApiJsonTest` passes on the JVM |
| iOS UI | Login, trips, join invite, itinerary, expense status/receipts | Not compiled — needs a Mac or the workflow |
| iOS integration | `LaravelRepository` (URLSession), token in the Keychain (this device only) | Not compiled — needs a Mac or the workflow |

Android's `HttpURLConnection` cannot send PATCH, so the Android client sends
POST with `X-HTTP-Method-Override: PATCH`, which Laravel honours. Web-only for
now: itemized/exact/percentage splits and editing per-stop budgets (the apps
show them read-only).

## Maps in the apps

Each itinerary day has an in-app map with numbered pins joined in visiting order.

- **Tap a place (or a pin, then "Go")** to start Google Maps turn-by-turn
  navigation **from your current location** to that stop. The travel mode
  follows the stop's transport. This hands off to the Google Maps app (or the
  browser when it is not installed); in-app turn-by-turn would need Google's
  paid Navigation SDK.
- **Day route** previews the whole day's stops in Google Maps.
- **Android** shows Google Maps when the build has a Maps SDK key, and falls
  back to OpenStreetMap without one. **iOS** uses Apple MapKit (no key).
- Typed addresses are looked up with the device geocoder (Android `Geocoder`,
  iOS `CLGeocoder`) and shown faded as approximate pins. Saving a stop from the
  app keeps its pin when the address is unchanged.

### Google Maps key for Android

1. In Google Cloud Console, enable **Maps SDK for Android** and create an API
   key. Restrict it to *Android apps* with package `com.barkadakaskas.app` and
   your signing certificate's SHA-1 (`./gradlew signingReport` shows the debug
   one), and to the Maps SDK for Android API.
2. Local builds: add `MAPS_API_KEY=your_key` to
   `android/BarKadaKaskas/local.properties` (git-ignored).
3. CI builds: add a repository secret named `MAPS_API_KEY`
   (Settings → Secrets and variables → Actions). The mobile workflow passes it
   to Gradle.

This is a different key from the web's `GOOGLE_MAPS_BROWSER_KEY`
(`docs/google-maps-setup.md`), which is restricted to your website.
