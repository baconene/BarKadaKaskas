<?php

namespace App\Http\Controllers;

use App\Support\PublicImages;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Storage;

/** The signed-in traveller's contact details, emergency contact and profile picture. */
class TravelerProfileController extends Controller
{
    public function show(Request $request)
    {
        return response()->json(['data' => $this->profile($request->user())]);
    }

    public function update(Request $request)
    {
        $phone = ['nullable', 'string', 'max:40', 'regex:/^[0-9+()\-\s.]{5,40}$/'];
        $data = $request->validate([
            'name' => 'sometimes|required|string|max:255',
            'phone' => $phone,
            'other_contact' => 'nullable|string|max:120',
            'emergency_contact_name' => 'nullable|string|max:120|required_with:emergency_contact_phone',
            'emergency_contact_relationship' => 'nullable|string|max:60',
            'emergency_contact_phone' => [...$phone, 'required_with:emergency_contact_name'],
        ], ['phone.regex' => 'Use digits and + ( ) - only.', 'emergency_contact_phone.regex' => 'Use digits and + ( ) - only.']);
        $request->user()->update(array_map(fn ($value) => is_string($value) ? (trim($value) ?: null) : $value, $data));
        return $request->is('api/*') ? response()->json(['data' => $this->profile($request->user()->fresh())]) : back();
    }

    public function storeAvatar(Request $request)
    {
        $request->validate(['avatar' => 'required|image|mimes:jpg,jpeg,png,webp|max:8192|dimensions:min_width=64,min_height=64']);
        $user = $request->user();
        $previous = $user->avatar_path;
        $user->update(['avatar_path' => PublicImages::store($request->file('avatar'), 'avatars')]);
        if ($previous) { Storage::disk('local')->delete($previous); }
        return $request->is('api/*') ? response()->json(['data' => $this->profile($user->fresh())]) : back();
    }

    public function destroyAvatar(Request $request)
    {
        $user = $request->user();
        if ($user->avatar_path) { Storage::disk('local')->delete($user->avatar_path); }
        $user->update(['avatar_path' => null]);
        return $request->is('api/*') ? response()->json(['data' => $this->profile($user->fresh())]) : back();
    }

    /** AI photo edits allowed per traveller in each window (profile pictures, covers and trip photos together). */
    public const AI_LIMIT = 3;
    public const AI_WINDOW = 4 * 3600;

    /**
     * Previews an AI-edited version of a photo (profile picture, trip cover or trip photo): a ready-made style or edit
     * template, or the traveller's own request. Nothing is stored; the traveller uploads the result where they want it.
     * Each traveller gets AI_LIMIT successful edits every AI_WINDOW seconds.
     */
    public function restyle(Request $request, \App\Services\Vertex\PhotoRestyler $restyler)
    {
        $request->validate([
            'photo' => 'required|image|mimes:jpg,jpeg,png,webp|max:8192',
            'style' => ['nullable', 'string', \Illuminate\Validation\Rule::in(array_merge(array_keys(\App\Services\Vertex\PhotoRestyler::STYLES), array_keys(\App\Services\Vertex\PhotoRestyler::EDITS)))],
            'prompt' => 'nullable|string|max:200|required_without:style',
            'subject' => 'nullable|in:portrait,photo',
        ]);
        $key = $this->aiKey($request);
        if (\Illuminate\Support\Facades\RateLimiter::tooManyAttempts($key, self::AI_LIMIT)) {
            $wait = \Illuminate\Support\Facades\RateLimiter::availableIn($key);
            return response()->json(['message' => 'You’ve used your '.self::AI_LIMIT.' AI edits for now. You can use AI again in '.$this->duration($wait).'.', 'ai' => $this->quota($request)], 429);
        }
        @set_time_limit(150);
        $file = $request->file('photo');
        try {
            $image = $restyler->restyle((string) file_get_contents($file->getRealPath()), $file->getMimeType() ?: 'image/jpeg', $request->input('style'), $request->input('prompt'), $request->input('subject', 'portrait'));
        } catch (\Illuminate\Http\Client\ConnectionException) {
            return response()->json(['message' => 'The AI took too long to answer. Please try again.', 'ai' => $this->quota($request)], 504);
        } catch (\RuntimeException $e) {
            return response()->json(['message' => $e->getMessage(), 'ai' => $this->quota($request)], in_array($e->getCode(), [422, 502, 503], true) ? $e->getCode() : 502);
        }
        // Only edits that produced an image count toward the limit.
        \Illuminate\Support\Facades\RateLimiter::hit($key, self::AI_WINDOW);
        return response()->json(['data' => ['image' => 'data:'.$image['mime'].';base64,'.$image['data']], 'ai' => $this->quota($request)]);
    }

    /** How many AI edits are left and when the window resets; also lists the ready-made templates. */
    public function aiQuota(Request $request)
    {
        return response()->json(['data' => $this->quota($request) + ['templates' => [
            'edits' => array_keys(\App\Services\Vertex\PhotoRestyler::EDITS),
            'styles' => \App\Services\Vertex\PhotoRestyler::SUBJECT_STYLES['photo'],
        ]]]);
    }

    private function aiKey(Request $request): string
    {
        return 'photo-ai:'.$request->user()->id;
    }

    /** @return array{limit: int, remaining: int, resets_in: int} */
    private function quota(Request $request): array
    {
        $key = $this->aiKey($request);
        return ['limit' => self::AI_LIMIT, 'remaining' => \Illuminate\Support\Facades\RateLimiter::remaining($key, self::AI_LIMIT),
            'resets_in' => \Illuminate\Support\Facades\RateLimiter::attempts($key) > 0 ? \Illuminate\Support\Facades\RateLimiter::availableIn($key) : 0];
    }

    private function duration(int $seconds): string
    {
        $hours = intdiv($seconds, 3600); $minutes = (int) ceil(($seconds % 3600) / 60);
        return $hours ? "{$hours}h {$minutes}m" : max(1, $minutes).' min';
    }

    public function avatar(string $file)
    {
        return PublicImages::serve('avatars', $file);
    }

    private function profile($user): array
    {
        return $user->only(['id', 'name', 'email', 'phone', 'other_contact', 'emergency_contact_name', 'emergency_contact_relationship', 'emergency_contact_phone', 'avatar_url']);
    }
}
