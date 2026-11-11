<?php

namespace Tests\Feature;

use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Http\UploadedFile;
use Illuminate\Support\Facades\Http;
use Tests\TestCase;

class PhotoRestyleTest extends TestCase
{
    use RefreshDatabase;

    protected function setUp(): void
    {
        parent::setUp();
        config(['services.gemini.api_key' => 'test-key', 'services.gemini.credentials' => null, 'services.gemini.project' => 'demo-project', 'services.gemini.location' => 'global', 'services.gemini.image_model' => 'nano-banana-test']);
    }

    private function headers(User $user): array
    {
        $this->app['auth']->forgetGuards();
        return ['Authorization' => 'Bearer '.$user->createToken('Test device', ['mobile'], now()->addDay())->plainTextToken];
    }

    public function test_restyle_returns_a_preview_image_from_the_image_model(): void
    {
        Http::fake(['aiplatform.googleapis.com/*' => Http::response(['candidates' => [['content' => ['parts' => [['text' => 'Here you go'], ['inlineData' => ['mimeType' => 'image/png', 'data' => base64_encode('PNGDATA')]]]]]]])]);
        $this->postJson('/api/v1/me/avatar/restyle', ['photo' => UploadedFile::fake()->image('me.jpg', 600, 600), 'style' => 'anime'], $this->headers(User::factory()->create()))
            ->assertOk()->assertJsonPath('data.image', 'data:image/png;base64,'.base64_encode('PNGDATA'));
        Http::assertSent(fn ($request) => str_contains($request->url(), 'projects/demo-project/locations/global/publishers/google/models/nano-banana-test:generateContent')
            && $request['generationConfig']['responseModalities'] === ['TEXT', 'IMAGE']
            && str_contains($request['contents'][0]['parts'][1]['text'], 'anime'));
    }

    public function test_custom_prompt_and_validation(): void
    {
        Http::fake(['aiplatform.googleapis.com/*' => Http::response(['candidates' => [['content' => ['parts' => [['inlineData' => ['mimeType' => 'image/png', 'data' => 'AAAA']]]]]]])]);
        $user = User::factory()->create();
        $this->postJson('/api/v1/me/avatar/restyle', ['photo' => UploadedFile::fake()->image('me.jpg', 600, 600), 'prompt' => 'as a Jollibee-loving astronaut'], $this->headers($user))->assertOk();
        $this->postJson('/api/v1/me/avatar/restyle', ['photo' => UploadedFile::fake()->image('me.jpg', 600, 600)], $this->headers($user))->assertUnprocessable()->assertJsonValidationErrors('prompt');
        $this->postJson('/api/v1/me/avatar/restyle', ['photo' => UploadedFile::fake()->image('me.jpg', 600, 600), 'style' => 'nope'], $this->headers($user))->assertUnprocessable()->assertJsonValidationErrors('style');
    }

    public function test_friendly_errors_for_missing_setup_model_or_image(): void
    {
        $user = User::factory()->create();
        Http::fake(['aiplatform.googleapis.com/*' => Http::sequence()
            ->push(['error' => ['status' => 'NOT_FOUND', 'message' => 'model not found']], 404)
            ->push(['candidates' => [['content' => ['parts' => [['text' => 'I cannot do that']]], 'finishReason' => 'STOP']]])]);
        $this->postJson('/api/v1/me/avatar/restyle', ['photo' => UploadedFile::fake()->image('me.jpg'), 'style' => 'sketch'], $this->headers($user))->assertStatus(503)->assertJsonPath('message', fn ($m) => str_contains($m, 'VERTEX_IMAGE_MODEL'));
        $this->postJson('/api/v1/me/avatar/restyle', ['photo' => UploadedFile::fake()->image('me.jpg'), 'style' => 'sketch'], $this->headers($user))->assertStatus(422);
        config(['services.gemini.api_key' => null]);
        $this->postJson('/api/v1/me/avatar/restyle', ['photo' => UploadedFile::fake()->image('me.jpg'), 'style' => 'sketch'], $this->headers($user))->assertStatus(503);
    }

    public function test_express_mode_404_in_us_central1_retries_at_the_global_location(): void
    {
        config(['services.gemini.project' => null]);
        $ok = ['candidates' => [['content' => ['parts' => [['inlineData' => ['mimeType' => 'image/png', 'data' => 'AAAA']]]]]]];
        Http::fake(['aiplatform.googleapis.com/*' => Http::sequence()
            ->push(['error' => ['status' => 'NOT_FOUND', 'message' => 'Publisher model `projects/1019187888243/locations/us-central1/publishers/google/models/nano-banana-test` was not found']], 404)
            ->push($ok)->push($ok)]);
        $user = User::factory()->create();
        $this->postJson('/api/v1/me/avatar/restyle', ['photo' => UploadedFile::fake()->image('me.jpg'), 'style' => 'sketch'], $this->headers($user))->assertOk();
        $this->postJson('/api/v1/me/avatar/restyle', ['photo' => UploadedFile::fake()->image('me.jpg'), 'style' => 'sketch'], $this->headers($user))->assertOk();
        $urls = Http::recorded()->map(fn ($pair) => $pair[0]->url())->all();
        $this->assertStringContainsString('/v1/publishers/google/models/nano-banana-test', $urls[0]);
        $this->assertStringContainsString('/v1/projects/1019187888243/locations/global/', $urls[1]);
        $this->assertStringContainsString('/v1/projects/1019187888243/locations/global/', $urls[2]); // remembered, no extra attempt
    }

    public function test_trip_photos_use_a_scene_prompt(): void
    {
        Http::fake(['aiplatform.googleapis.com/*' => Http::response(['candidates' => [['content' => ['parts' => [['inlineData' => ['mimeType' => 'image/png', 'data' => 'AAAA']]]]]]])]);
        $this->postJson('/api/v1/photos/restyle', ['photo' => UploadedFile::fake()->image('falls.jpg', 1600, 900), 'style' => 'golden-hour', 'subject' => 'photo'], $this->headers(User::factory()->create()))->assertOk();
        Http::assertSent(fn ($request) => str_contains($request['contents'][0]['parts'][1]['text'], 'travel photo') && str_contains($request['contents'][0]['parts'][1]['text'], 'golden-hour'));
    }

    public function test_edit_templates_keep_the_photo_real(): void
    {
        Http::fake(['aiplatform.googleapis.com/*' => Http::response(['candidates' => [['content' => ['parts' => [['inlineData' => ['mimeType' => 'image/png', 'data' => 'AAAA']]]]]]])]);
        $user = User::factory()->create();
        $this->postJson('/api/v1/photos/restyle', ['photo' => UploadedFile::fake()->image('beach.jpg', 1200, 800), 'style' => 'blue-sky', 'subject' => 'photo'], $this->headers($user))->assertOk()->assertJsonPath('ai.remaining', 2);
        Http::assertSent(fn ($request) => str_contains($request['contents'][0]['parts'][1]['text'], 'Edit this travel photo') && str_contains($request['contents'][0]['parts'][1]['text'], 'clear blue sky'));
        $this->getJson('/api/v1/photos/ai-quota', $this->headers($user))->assertOk()->assertJsonPath('data.limit', 3)->assertJsonPath('data.remaining', 2)
            ->assertJsonPath('data.templates.edits', fn ($edits) => in_array('remove-people', $edits, true));
    }

    public function test_three_ai_edits_per_traveller_every_four_hours(): void
    {
        Http::fake(['aiplatform.googleapis.com/*' => Http::response(['candidates' => [['content' => ['parts' => [['inlineData' => ['mimeType' => 'image/png', 'data' => 'AAAA']]]]]]])]);
        $user = User::factory()->create();
        $edit = fn () => $this->postJson('/api/v1/photos/restyle', ['photo' => UploadedFile::fake()->image('a.jpg'), 'style' => 'enhance', 'subject' => 'photo'], $this->headers($user));
        $edit()->assertOk(); $edit()->assertOk(); $edit()->assertOk()->assertJsonPath('ai.remaining', 0);
        $edit()->assertStatus(429)->assertJsonPath('ai.remaining', 0)->assertJsonPath('message', fn ($m) => str_contains($m, '3 AI edits'));
        Http::assertSentCount(3);
        // Someone else still has their own three.
        $this->postJson('/api/v1/photos/restyle', ['photo' => UploadedFile::fake()->image('a.jpg'), 'style' => 'enhance', 'subject' => 'photo'], $this->headers(User::factory()->create()))->assertOk();
        // Four hours later the limit resets.
        $this->travel(4 * 60 + 1)->minutes();
        $edit()->assertOk();
    }
}
