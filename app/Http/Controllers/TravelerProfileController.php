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

    /** Previews an AI-restyled version of a photo; the traveller saves it with storeAvatar if they like it. */
    public function restyle(Request $request, \App\Services\Vertex\PhotoRestyler $restyler)
    {
        $request->validate([
            'photo' => 'required|image|mimes:jpg,jpeg,png,webp|max:8192',
            'style' => ['nullable', 'string', \Illuminate\Validation\Rule::in(array_keys(\App\Services\Vertex\PhotoRestyler::STYLES))],
            'prompt' => 'nullable|string|max:200|required_without:style',
        ]);
        @set_time_limit(150);
        $file = $request->file('photo');
        try {
            $image = $restyler->restyle((string) file_get_contents($file->getRealPath()), $file->getMimeType() ?: 'image/jpeg', $request->input('style'), $request->input('prompt'));
        } catch (\Illuminate\Http\Client\ConnectionException) {
            return response()->json(['message' => 'The AI took too long to answer. Please try again.'], 504);
        } catch (\RuntimeException $e) {
            return response()->json(['message' => $e->getMessage()], in_array($e->getCode(), [422, 502, 503], true) ? $e->getCode() : 502);
        }
        return response()->json(['data' => ['image' => 'data:'.$image['mime'].';base64,'.$image['data']]]);
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
