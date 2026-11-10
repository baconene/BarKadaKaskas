<?php

namespace Tests\Feature;

use App\Models\{Trip, TripPost, User};
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Http\UploadedFile;
use Illuminate\Support\Facades\Storage;
use Tests\TestCase;

class TripPostTest extends TestCase
{
    use RefreshDatabase;

    private User $owner;
    private User $friend;
    private User $other;
    private Trip $trip;

    protected function setUp(): void
    {
        parent::setUp();
        Storage::fake('local');
        [$this->owner, $this->friend, $this->other] = [User::factory()->create(), User::factory()->create(), User::factory()->create()];
        $this->trip = Trip::create(['organizer_user_id' => $this->owner->id, 'name' => 'Siquijor', 'destination' => 'Siquijor', 'starts_on' => '2026-11-11', 'ends_on' => '2026-11-17']);
        foreach ([$this->owner, $this->friend, $this->other] as $user) { $this->trip->members()->create(['user_id' => $user->id, 'status' => 'active']); }
    }

    private function headers(User $user): array
    {
        $this->app['auth']->forgetGuards();
        return ['Authorization' => 'Bearer '.$user->createToken('Test device', ['mobile'], now()->addDay())->plainTextToken];
    }

    private function sharePhotos(User $user, int $photos = 2, string $caption = 'Cambugahay Falls!'): array
    {
        $files = array_map(fn ($i) => UploadedFile::fake()->image("p{$i}.jpg", 1200, 800), range(1, $photos));
        return $this->postJson("/api/v1/trips/{$this->trip->id}/posts", ['caption' => $caption, 'photos' => $files], $this->headers($user))->assertCreated()->json('data');
    }

    public function test_members_post_photos_and_see_the_feed_newest_first(): void
    {
        $first = $this->sharePhotos($this->friend, 2);
        $this->assertSame('Cambugahay Falls!', $first['caption']);
        $this->assertCount(2, $first['photos']);
        $this->assertSame(1200, $first['photos'][0]['width']);
        $this->assertTrue($first['can_delete']);
        $second = $this->sharePhotos($this->other, 1, '');

        $feed = $this->getJson("/api/v1/trips/{$this->trip->id}/posts", $this->headers($this->owner))->assertOk();
        $this->assertSame([$second['id'], $first['id']], array_column($feed->json('data'), 'id'));
        $this->assertNull($feed->json('data.0.caption'));
        $this->assertTrue($feed->json('data.0.can_delete')); // organizer may remove any post
        $this->assertNull($feed->json('next_before'));

        $this->app['auth']->forgetGuards();
        $this->get(parse_url($first['photos'][0]['url'], PHP_URL_PATH))->assertOk();
    }

    public function test_outsiders_cannot_see_or_post(): void
    {
        $stranger = User::factory()->create();
        $this->getJson("/api/v1/trips/{$this->trip->id}/posts", $this->headers($stranger))->assertForbidden();
        $this->postJson("/api/v1/trips/{$this->trip->id}/posts", ['photos' => [UploadedFile::fake()->image('a.jpg')]], $this->headers($stranger))->assertForbidden();
    }

    public function test_likes_toggle_and_count(): void
    {
        $post = $this->sharePhotos($this->friend, 1);
        $this->postJson("/api/v1/trips/{$this->trip->id}/posts/{$post['id']}/like", [], $this->headers($this->owner))->assertOk()->assertJsonPath('data.liked', true)->assertJsonPath('data.likes_count', 1);
        $this->postJson("/api/v1/trips/{$this->trip->id}/posts/{$post['id']}/like", [], $this->headers($this->other))->assertJsonPath('data.likes_count', 2);
        $this->postJson("/api/v1/trips/{$this->trip->id}/posts/{$post['id']}/like", [], $this->headers($this->owner))->assertJsonPath('data.liked', false)->assertJsonPath('data.likes_count', 1);
        $this->getJson("/api/v1/trips/{$this->trip->id}/posts", $this->headers($this->other))->assertJsonPath('data.0.liked', true)->assertJsonPath('data.0.likes_count', 1);
    }

    public function test_only_the_author_or_organizer_deletes_and_files_are_removed(): void
    {
        $post = $this->sharePhotos($this->friend, 1);
        $path = TripPost::find($post['id'])->photos()->first()->path;
        $this->deleteJson("/api/v1/trips/{$this->trip->id}/posts/{$post['id']}", [], $this->headers($this->other))->assertForbidden();
        $this->deleteJson("/api/v1/trips/{$this->trip->id}/posts/{$post['id']}", [], $this->headers($this->owner))->assertNoContent();
        Storage::disk('local')->assertMissing($path);
        $this->assertSame(0, TripPost::count());
    }

    public function test_requires_photos_and_pages_long_feeds(): void
    {
        $this->postJson("/api/v1/trips/{$this->trip->id}/posts", ['caption' => 'Text only'], $this->headers($this->friend))->assertUnprocessable()->assertJsonValidationErrors('photos');
        foreach (range(1, 16) as $i) { TripPost::create(['trip_id' => $this->trip->id, 'trip_member_id' => $this->trip->members()->first()->id, 'caption' => "Post {$i}"]); }
        $page = $this->getJson("/api/v1/trips/{$this->trip->id}/posts", $this->headers($this->friend))->assertJsonCount(15, 'data');
        $this->getJson("/api/v1/trips/{$this->trip->id}/posts?before=".$page->json('next_before'), $this->headers($this->friend))->assertJsonCount(1, 'data')->assertJsonPath('next_before', null);
    }

    public function test_members_comment_and_the_right_people_can_remove_comments(): void
    {
        $post = $this->sharePhotos($this->friend, 1);
        $url = "/api/v1/trips/{$this->trip->id}/posts/{$post['id']}/comments";
        $this->assertSame(0, $post['comments_count']);
        $mine = $this->postJson($url, ['body' => '  Ganda!  '], $this->headers($this->other))->assertCreated()->assertJsonPath('data.body', 'Ganda!')->assertJsonPath('comments_count', 1)->json('data');
        $this->assertTrue($mine['can_delete']);
        $this->postJson($url, ['body' => 'Sana all'], $this->headers($this->owner))->assertCreated();
        $this->postJson($url, ['body' => ''], $this->headers($this->owner))->assertJsonValidationErrors('body');

        $list = $this->getJson($url, $this->headers($this->friend))->assertOk()->json('data');
        $this->assertSame(['Ganda!', 'Sana all'], array_column($list, 'body'));
        $this->assertTrue($list[0]['can_delete']); // the post's author may remove comments on their post
        $this->assertSame(2, $this->getJson("/api/v1/trips/{$this->trip->id}/posts", $this->headers($this->owner))->json('data.0.comments_count'));

        $outsider = User::factory()->create();
        $this->getJson($url, $this->headers($outsider))->assertForbidden();
        // A different member who is neither the comment's author, the post's author nor the organizer cannot remove it.
        $third = User::factory()->create(); $this->trip->members()->create(['user_id' => $third->id, 'status' => 'active']);
        $this->deleteJson("{$url}/{$mine['id']}", [], $this->headers($third))->assertForbidden();
        $this->deleteJson("{$url}/{$mine['id']}", [], $this->headers($this->friend))->assertOk()->assertJsonPath('comments_count', 1);

        // Deleting the post removes its comments too.
        $this->deleteJson("/api/v1/trips/{$this->trip->id}/posts/{$post['id']}", [], $this->headers($this->friend))->assertNoContent();
        $this->assertDatabaseCount('trip_post_comments', 0);
    }
}
