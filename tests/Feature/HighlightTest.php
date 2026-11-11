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

    /** Posts a photo at a local Manila time on Nov 12. */
    private function postAt(string $time, User $user, string $caption): array
    {
        Carbon::setTestNow(Carbon::parse("2026-11-12 {$time}", 'Asia/Manila'));
        return $this->postJson("/api/v1/trips/{$this->trip->id}/posts", ['caption' => $caption, 'photos' => [UploadedFile::fake()->image('p.jpg', 800, 600)]], $this->headers($user))->assertCreated()->json('data');
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
        $this->assertSame(['7:00 AM', '7:00 AM', '12:00 PM'], array_column($data['slides'], 'time'));
        $this->assertSame(['Cambugahay Falls', 'Cambugahay Falls', 'Lunch at Baha Bar'], array_column($data['slides'], 'title'));
        $this->assertSame(['Early start', 'Falls', 'Lunch!'], array_column($data['slides'], 'caption'));
        $this->assertEqualsWithDelta(30, $data['cover_duration'] + $data['outro_duration'] + array_sum(array_column($data['slides'], 'duration')), 0.1);
        $this->assertStringEndsWith('/audio/highlight-travel.mp3', $data['music_url']);
        $this->assertSame(['2026-11-13', '2026-11-12'], $data['days']);
        $this->assertNull($data['cover_url']);

        $this->getJson("/api/v1/trips/{$this->trip->id}/highlights", $this->headers(User::factory()->create()))->assertForbidden();
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
