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
        $this->postJson('/api/v1/me/avatar/restyle', ['photo' => UploadedFile::fake()->image('me.jpg'), 'style' => 'sketch'], $this->headers($user))->assertStatus(502)->assertJsonPath('message', fn ($m) => str_contains($m, 'VERTEX_IMAGE_MODEL'));
        $this->postJson('/api/v1/me/avatar/restyle', ['photo' => UploadedFile::fake()->image('me.jpg'), 'style' => 'sketch'], $this->headers($user))->assertStatus(422);
        config(['services.gemini.api_key' => null]);
        $this->postJson('/api/v1/me/avatar/restyle', ['photo' => UploadedFile::fake()->image('me.jpg'), 'style' => 'sketch'], $this->headers($user))->assertStatus(503);
    }
}
