<?php

namespace Tests\Feature;

use App\Models\{Trip, User};
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Http\UploadedFile;
use Illuminate\Support\Carbon;
use Illuminate\Support\Facades\{Http, Storage};
use Tests\TestCase;

class HighlightTest extends TestCase
{
    use RefreshDatabase;

    private User $owner;
    private User $friend;
    private Trip $trip;

    protected function setUp(): void
    {
        parent::setUp();
        Storage::fake('local');
        config(['services.gemini.api_key' => 'test-key', 'services.gemini.credentials' => null, 'services.gemini.project' => 'demo']);
        [$this->owner, $this->friend] = [User::factory()->create(), User::factory()->create()];
        $this->trip = Trip::create(['organizer_user_id' => $this->owner->id, 'name' => 'Siquijor Barkada Trip', 'destination' => 'Siquijor', 'starts_on' => '2026-11-10', 'ends_on' => '2026-11-17', 'timezone' => 'Asia/Manila', 'currency_code' => 'PHP']);
        foreach ([$this->owner, $this->friend] as $user) { $this->trip->members()->create(['user_id' => $user->id, 'status' => 'active']); }
    }

    protected function tearDown(): void
    {
        Carbon::setTestNow();
        parent::tearDown();
    }

    private function headers(User $user): array
    {
        $this->app['auth']->forgetGuards();
        return ['Authorization' => 'Bearer '.$user->createToken('Test device', ['mobile'], now()->addDay())->plainTextToken];
    }

    /** A simple JPEG "scene": sky, sea, sun and a tree; different arguments give different-looking photos. */
    private function scene(array $sky, array $sea, int $sunX, int $shift = 0, int $bright = 0): UploadedFile
    {
        $im = imagecreatetruecolor(800, 600);
        $tone = fn (array $c) => imagecolorallocate($im, ...array_map(fn ($v) => min(255, $v + $bright), $c));
        imagefilledrectangle($im, 0, 0, 800, 270 + $shift, $tone($sky));
        imagefilledrectangle($im, 0, 270 + $shift, 800, 600, $tone($sea));
        imagefilledellipse($im, $sunX, 120 + $shift, 90, 90, imagecolorallocate($im, 255, 230, 120));
        imagefilledrectangle($im, 480, 180 + $shift, 560, 540, imagecolorallocate($im, 40, 90, 40));
        ob_start(); imagejpeg($im, null, 85);
        return UploadedFile::fake()->createWithContent('p.jpg', (string) ob_get_clean());
    }

    /** Posts a photo at a local Manila time on Nov 12 (a different-looking photo each time unless one is given). */
    private function postAt(string $time, User $user, string $caption, ?UploadedFile $photo = null): array
    {
        static $n = 0;
        // Clearly different views (checked to not count as repeats of each other).
        $views = [[[120, 170, 230], [30, 90, 160], 200, 0], [[240, 150, 90], [60, 40, 80], 600, -80], [[120, 170, 230], [200, 190, 150], 650, 60], [[40, 40, 70], [230, 230, 220], 120, 140]];
        [$sky, $sea, $sun, $shift] = $views[$n++ % count($views)];
        $photo ??= $this->scene($sky, $sea, $sun, $shift);
        Carbon::setTestNow(Carbon::parse("2026-11-12 {$time}", 'Asia/Manila'));
        return $this->postJson("/api/v1/trips/{$this->trip->id}/posts", ['caption' => $caption, 'photos' => [$photo]], $this->headers($user))->assertCreated()->json('data');
    }

    public function test_the_day_highlight_follows_the_itinerary_with_that_days_photos(): void
    {
        foreach ([['07:00', 'Cambugahay Falls'], ['12:00', 'Lunch at Baha Bar']] as [$time, $title]) {
            $this->trip->itineraryItems()->create(['title' => $title, 'starts_at' => Carbon::parse("2026-11-12 {$time}", 'Asia/Manila')->utc(), 'ends_at' => Carbon::parse("2026-11-12 {$time}", 'Asia/Manila')->addHour()->utc(), 'timezone' => 'Asia/Manila', 'created_by_user_id' => $this->owner->id]);
        }
        $lunch = $this->postAt('12:30', $this->owner, 'Lunch!');
        $this->postAt('07:40', $this->friend, 'Falls');
        $this->postAt('06:30', $this->friend, 'Early start');   // before the first stop: goes with it
        Carbon::setTestNow(Carbon::parse('2026-11-13 09:00', 'Asia/Manila'));
        $this->postJson("/api/v1/trips/{$this->trip->id}/posts", ['photos' => [UploadedFile::fake()->image('x.jpg')]], $this->headers($this->owner))->assertCreated(); // another day
        $this->postJson("/api/v1/trips/{$this->trip->id}/posts/{$lunch['id']}/like", [], $this->headers($this->friend))->assertOk();

        $data = $this->getJson("/api/v1/trips/{$this->trip->id}/highlights?date=2026-11-12", $this->headers($this->owner))->assertOk()->json('data');
        $this->assertSame('Day 3 · Thu, Nov 12', $data['subtitle']);
        // The liked lunch photo is the cover, so it isn't repeated in the slides.
        $this->assertSame($lunch['photos'][0]['url'] ?? $data['base_photo_url'], $data['base_photo_url']);
        $this->assertSame(['7:00 AM', '7:00 AM'], array_column($data['slides'], 'time'));
        $this->assertSame(['Cambugahay Falls', 'Cambugahay Falls'], array_column($data['slides'], 'title'));
        $this->assertSame(['Early start', 'Falls'], array_column($data['slides'], 'caption'));
        $this->assertSame(['eyebrow' => 'Day 3 of 8', 'title' => 'Siquijor Barkada Trip', 'date' => 'Thursday, November 12, 2026', 'destination' => 'Siquijor',
            'route' => 'Cambugahay Falls → Lunch at Baha Bar', 'stats' => '2 stops · 3 photos · 2 travellers'], $data['cover']);
        $this->assertEqualsWithDelta(30, $data['cover_duration'] + $data['outro_duration'] + array_sum(array_column($data['slides'], 'duration')), 0.1);
        $this->assertStringEndsWith('/audio/highlight-travel.mp3', $data['music_url']);
        $this->assertSame(['2026-11-13', '2026-11-12'], $data['days']);
        $this->assertNull($data['cover_url']);

        $this->getJson("/api/v1/trips/{$this->trip->id}/highlights", $this->headers(User::factory()->create()))->assertForbidden();
    }

    public function test_the_reel_has_a_scene_per_stop_with_the_trip_there(): void
    {
        config(['services.google_maps.browser_key' => '']);   // no maps: scenes still come, without a route map
        foreach ([['07:00', 'Cambugahay Falls', null], ['12:00', 'Lunch at Baha Bar', 'tricycle']] as [$time, $title, $mode]) {
            $this->trip->itineraryItems()->create(['title' => $title, 'transport_mode' => $mode, 'starts_at' => Carbon::parse("2026-11-12 {$time}", 'Asia/Manila')->utc(), 'ends_at' => Carbon::parse("2026-11-12 {$time}", 'Asia/Manila')->addHour()->utc(), 'timezone' => 'Asia/Manila', 'created_by_user_id' => $this->owner->id]);
        }
        $best = $this->postAt('07:20', $this->owner, 'Jump!');
        $this->postAt('07:50', $this->friend, 'Swim');
        $this->postAt('12:30', $this->friend, 'Kinilaw');
        $this->postJson("/api/v1/trips/{$this->trip->id}/posts/{$best['id']}/like", [], $this->headers($this->friend))->assertOk();

        $scenes = $this->getJson("/api/v1/trips/{$this->trip->id}/highlights?date=2026-11-12", $this->headers($this->owner))->assertOk()->json('data.scenes');
        $this->assertSame(['Cambugahay Falls', 'Lunch at Baha Bar'], array_column($scenes, 'title'));
        $this->assertNull($scenes[0]['travel']);   // the day's first stop
        $this->assertSame(['mode' => 'tricycle', 'label' => 'By tricycle', 'from' => 'Cambugahay Falls', 'map' => null, 'duration' => 2.6], $scenes[1]['travel']);
        $this->assertSame(['Swim'], array_column($scenes[0]['photos'], 'caption'));   // "Jump!" is the cover
        $this->assertSame(['Kinilaw'], array_column($scenes[1]['photos'], 'caption'));
        $this->assertNotEmpty($scenes[1]['photos'][0]['by']);
    }

        public function test_photos_go_to_the_stop_where_they_were_taken(): void
    {
        config(['services.google_maps.browser_key' => '']);
        $stop = fn ($time, $title, $lat, $lng) => $this->trip->itineraryItems()->create(['title' => $title, 'latitude' => $lat, 'longitude' => $lng, 'starts_at' => Carbon::parse("2026-11-12 {$time}", 'Asia/Manila')->utc(), 'ends_at' => Carbon::parse("2026-11-12 {$time}", 'Asia/Manila')->addHours(2)->utc(), 'timezone' => 'Asia/Manila', 'created_by_user_id' => $this->owner->id]);
        $stop('08:00', 'Cambugahay Falls', 9.1405, 123.6227);
        $stop('13:00', 'Salagdoong Beach', 9.2208, 123.6731);
        // Posted at 2 PM (during the beach), but taken at the falls in the morning: the photo's own place and time win.
        Carbon::setTestNow(Carbon::parse('2026-11-12 14:00', 'Asia/Manila'));
        // First post (best by default) becomes the cover, so the two below are both slides.
        $this->post("/api/v1/trips/{$this->trip->id}/posts", ['caption' => 'Cover', 'photos' => [$this->scene([40, 40, 70], [230, 230, 220], 120, 140)]], $this->headers($this->friend) + ['Accept' => 'application/json'])->assertCreated();
        $this->post("/api/v1/trips/{$this->trip->id}/posts", ['caption' => 'Falls pic', 'photos' => [$this->scene([120, 170, 230], [30, 90, 160], 200)],
            'photo_locations' => [['latitude' => 9.1409, 'longitude' => 123.6230, 'taken_at' => '2026-11-12 09:15:00']], 'latitude' => 9.2210, 'longitude' => 123.6735],
            $this->headers($this->owner) + ['Accept' => 'application/json'])->assertCreated();
        // No photo location: the phone's location when posting is used (at the beach).
        $this->post("/api/v1/trips/{$this->trip->id}/posts", ['caption' => 'Beach pic', 'photos' => [$this->scene([240, 150, 90], [60, 40, 80], 600, -80)], 'latitude' => 9.2210, 'longitude' => 123.6735],
            $this->headers($this->friend) + ['Accept' => 'application/json'])->assertCreated();
        $photo = \App\Models\TripPostPhoto::whereHas('post', fn ($q) => $q->where('caption', 'Falls pic'))->firstOrFail();
        $this->assertSame(['photo', '2026-11-12 01:15'], [$photo->location_source, $photo->taken_at->format('Y-m-d H:i')]);
        $this->assertSame('device', \App\Models\TripPostPhoto::whereHas('post', fn ($q) => $q->where('caption', 'Beach pic'))->value('location_source'));

        $slides = collect($this->getJson("/api/v1/trips/{$this->trip->id}/highlights?date=2026-11-12", $this->headers($this->owner))->assertOk()->json('data.slides'))->keyBy('caption');
        $this->assertSame('Cambugahay Falls', $slides['Falls pic']['title'] ?? null);
        $this->assertSame('Salagdoong Beach', $slides['Beach pic']['title'] ?? null);
    }

    public function test_posts_show_a_place_that_the_poster_can_change(): void
    {
        config(['services.google_maps.browser_key' => '']);
        $mk = fn ($time, $title, $lat, $lng) => $this->trip->itineraryItems()->create(['title' => $title, 'latitude' => $lat, 'longitude' => $lng, 'starts_at' => Carbon::parse("2026-11-12 {$time}", 'Asia/Manila')->utc(), 'ends_at' => Carbon::parse("2026-11-12 {$time}", 'Asia/Manila')->addHours(2)->utc(), 'timezone' => 'Asia/Manila', 'created_by_user_id' => $this->owner->id]);
        $falls = $mk('08:00', 'Cambugahay Falls', 9.1405, 123.6227);
        $beach = $mk('13:00', 'Salagdoong Beach', 9.2208, 123.6731);
        Carbon::setTestNow(Carbon::parse('2026-11-12 09:00', 'Asia/Manila'));
        $post = $this->post("/api/v1/trips/{$this->trip->id}/posts", ['photos' => [$this->scene([120, 170, 230], [30, 90, 160], 200)], 'latitude' => 9.1408, 'longitude' => 123.6229],
            $this->headers($this->owner) + ['Accept' => 'application/json'])->assertCreated()->json('data');
        $this->assertSame(['name' => 'Cambugahay Falls', 'itinerary_item_id' => $falls->id], $post['place']);   // nearest stop
        $this->assertTrue($post['can_edit_place']);

        $url = "/api/v1/trips/{$this->trip->id}/posts/{$post['id']}/place";
        $this->patchJson($url, ['name' => 'Somewhere'], $this->headers($this->friend))->assertForbidden();    // not theirs
        $this->patchJson($url, ['itinerary_item_id' => $beach->id], $this->headers($this->owner))->assertOk()->assertJsonPath('data.place.name', 'Salagdoong Beach');
        $photo = \App\Models\TripPostPhoto::where('trip_post_id', $post['id'])->firstOrFail();
        $this->assertSame([9.2208, 123.6731, 'manual'], [$photo->latitude, $photo->longitude, $photo->location_source]);
        // The chosen stop decides the highlight, even though the photo was taken in the morning.
        $cover = $this->postAt('09:30', $this->friend, 'Cover');
        $this->postJson("/api/v1/trips/{$this->trip->id}/posts/{$cover['id']}/like", [], $this->headers($this->owner))->assertOk();   // the cover, so ours is a slide
        $this->assertSame('Salagdoong Beach', collect($this->getJson("/api/v1/trips/{$this->trip->id}/highlights?date=2026-11-12", $this->headers($this->owner))->json('data.slides'))->firstWhere('caption', null)['title'] ?? null);

        $this->patchJson($url, ['name' => 'Lazi Church'], $this->headers($this->owner))->assertOk()->assertJsonPath('data.place', ['name' => 'Lazi Church', 'itinerary_item_id' => null]);
        $this->patchJson($url, ['clear' => true], $this->headers($this->owner))->assertOk()->assertJsonPath('data.place', null);
        $this->patchJson($url, [], $this->headers($this->owner))->assertStatus(422);
    }

    public function test_repeated_scenery_is_shown_once(): void
    {
        $this->postAt('08:00', $this->owner, 'Beach', $this->scene([120, 170, 230], [30, 90, 160], 200));
        $this->postAt('08:01', $this->friend, 'Beach again', $this->scene([120, 170, 230], [30, 90, 160], 205, 8, 12)); // same view, a little brighter
        $this->postAt('09:00', $this->friend, 'Sunset', $this->scene([240, 150, 90], [60, 40, 80], 600, -80));
        $this->postAt('10:00', $this->owner, 'Sand', $this->scene([120, 170, 230], [200, 190, 150], 650, 60));

        $data = $this->getJson("/api/v1/trips/{$this->trip->id}/highlights?date=2026-11-12", $this->headers($this->owner))->assertOk()->json('data');
        $shown = array_merge([$data['base_photo_url']], array_column($data['slides'], 'photo_url'));
        $this->assertCount(3, $shown);                       // cover + 2 slides; "Beach again" left out
        $this->assertCount(3, array_unique($shown));         // and nothing appears twice
        $this->assertNotContains('Beach again', array_column($data['slides'], 'caption'));
    }

    public function test_the_ai_cover_is_made_once_and_shared(): void
    {
        $this->postAt('09:00', $this->friend, 'Beach');
        Http::fake(['aiplatform.googleapis.com/*' => Http::response(['candidates' => [['content' => ['parts' => [['inlineData' => ['mimeType' => 'image/png', 'data' => base64_encode('PNG')]]]]]]])]);
        $url = $this->postJson("/api/v1/trips/{$this->trip->id}/highlights/cover?date=2026-11-12", [], $this->headers($this->owner))->assertOk()->json('data.cover_url');
        $this->assertStringContainsString('/trip-photos/', $url);
        Http::assertSent(fn ($request) => str_contains($request['contents'][0]['parts'][1]['text'], 'cinematic opening shot'));
        $this->assertSame($url, $this->getJson("/api/v1/trips/{$this->trip->id}/highlights?date=2026-11-12", $this->headers($this->friend))->json('data.cover_url'));
        $this->get(parse_url($url, PHP_URL_PATH))->assertOk();
        $this->postJson("/api/v1/trips/{$this->trip->id}/highlights/cover?date=2026-11-14", [], $this->headers($this->owner))->assertStatus(422); // no photos that day
    }
}
