<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\User;
use Illuminate\Auth\Events\Registered;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\{DB, Hash};
use Illuminate\Validation\Rules\Password;
use Illuminate\Validation\ValidationException;

class AuthController extends Controller
{
    public function register(Request $request)
    {
        $request->merge(['email' => mb_strtolower(trim((string) $request->input('email')))]);
        $data = $request->validate([
            'name' => 'required|string|max:255', 'email' => 'required|string|email|max:255|unique:users,email',
            'password' => ['required', 'confirmed', Password::defaults()], 'device_name' => 'sometimes|string|max:120',
        ]);
        [$user, $token] = DB::transaction(function () use ($data) {
            $user = User::create(['name' => $data['name'], 'email' => $data['email'], 'password' => $data['password']]);
            return [$user, $user->createToken($data['device_name'] ?? 'Mobile app', ['mobile'], now()->addDays(30))->plainTextToken];
        });
        event(new Registered($user));
        return response()->json(['token' => $token, 'user' => $user->only(['id', 'name', 'email'])], 201)->header('Cache-Control', 'no-store');
    }

    public function login(Request $request)
    {
        $data = $request->validate(['email' => 'required|string|email|max:255', 'password' => 'required|string', 'device_name' => 'sometimes|string|max:120']);
        // Case-insensitive on every database: web sign-up does not lowercase emails, and SQLite compares case-sensitively.
        $user = User::whereRaw('LOWER(email) = ?', [mb_strtolower(trim($data['email']))])->first();
        if (! $user || ! Hash::check($data['password'], $user->password)) {
            throw ValidationException::withMessages(['email' => 'The provided credentials are incorrect.']);
        }
        return response()->json(['token' => $user->createToken($data['device_name'] ?? 'Mobile app', ['mobile'], now()->addDays(30))->plainTextToken, 'user' => $user->only(['id', 'name', 'email'])])->header('Cache-Control', 'no-store');
    }

    public function me(Request $request)
    {
        return response()->json(['user' => $request->user()->only(['id', 'name', 'email'])]);
    }

    public function logout(Request $request)
    {
        $request->user()->currentAccessToken()?->delete();
        return response()->noContent();
    }
}
