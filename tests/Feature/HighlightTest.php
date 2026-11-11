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
        static $n = 0; $n++;
        $photo ??= $this->scene([($n * 70) % 255, 150, (255 - $n * 50) % 255], [30, ($n * 90) % 255, 160], 100 + ($n * 170) % 600, ($n % 3) * 60 - 60);
        Carbon::setTestNow(Carbon::parse("2026-11-12 {$time}", 'Asia/Manila'));
        return $this->postJson("/api/v1/trips/{$this->trip->id}/posts", ['caption' => $caption, 'photos' => [$photo]], $this->headers($user))->assertCreated()->json('data');
    }

    public function test_the_day_highlight_follows_the_itinerary_with_that_days_photos(): void
    {
        foreach ([['07:00', 'Cambugahay Falls'], ['12:00', 'Lunch at Baha Bar']] as [$time, $title]) {
            $this->trip->itineraryItems()->create(['title' => $title, 'starts_at' => Carbon::parse("2026-11-12 {$time}", 'Asia/Manila')->utc(), 'ends_at' => Carbon::parse("2026-11-12 {$time}", 'Asia/Manila')->addHour()->utc(), 'timezone' => 'Asia/Manila']);
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
