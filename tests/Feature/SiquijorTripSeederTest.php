<?php

namespace Tests\Feature;

use App\Models\Trip;
use App\Models\User;
use Database\Seeders\SiquijorTripSeeder;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Hash;
use Tests\TestCase;

class SiquijorTripSeederTest extends TestCase
{
    use RefreshDatabase;

    /** Per-person [min, max] in pesos for each day, from the trip plan. */
    private const DAILY_PER_PERSON = [
        '2026-11-11' => [920, 1500],
        '2026-11-12' => [1360, 2150],
        '2026-11-13' => [1430, 2280],
        '2026-11-14' => [1400, 2220],
        '2026-11-15' => [1410, 2250],
        '2026-11-16' => [1780, 3000],
        '2026-11-17' => [160, 300],
    ];

    private function trip(): Trip
    {
        return Trip::where('name', SiquijorTripSeeder::TRIP_NAME)->firstOrFail();
    }

    public function test_it_seeds_the_organizer_trip_and_a_fully_pinned_itinerary(): void
    {
        $this->seed(SiquijorTripSeeder::class);

        $organizer = User::where('email', SiquijorTripSeeder::ORGANIZER_EMAIL)->firstOrFail();
        $this->assertTrue(Hash::check('password', $organizer->password));
        $this->assertNotNull($organizer->email_verified_at);

        $trip = $this->trip();
        $this->assertSame($organizer->id, $trip->organizer_user_id);
        $this->assertSame(['2026-11-11', '2026-11-17'], [$trip->starts_on->format('Y-m-d'), $trip->ends_on->format('Y-m-d')]);
        $this->assertTrue($trip->members()->where('user_id', $organizer->id)->exists());

        $items = $trip->itineraryItems()->orderBy('starts_at')->get();
        $this->assertCount(43, $items);
        foreach ($items as $item) {
            $this->assertNotEmpty($item->location, $item->title);
            $this->assertNotNull($item->latitude, $item->title);
            $this->assertNotNull($item->longitude, $item->title);
            $this->assertTrue($item->ends_at->gte($item->starts_at), $item->title);
        }
        $this->assertNull($items->first()->transport_mode);
        $this->assertSame('flight', $items->last()->transport_mode);
        $this->assertSame('Arrive in Manila', $items->last()->title);
    }

    public function test_daily_per_person_budgets_match_the_trip_plan(): void
    {
        $this->seed(SiquijorTripSeeder::class);

        $byDay = $this->trip()->itineraryItems()->get()
            ->groupBy(fn ($item) => $item->starts_at->setTimezone('Asia/Manila')->format('Y-m-d'));

        $this->assertSame(array_keys(self::DAILY_PER_PERSON), $byDay->keys()->sort()->values()->all());
        foreach (self::DAILY_PER_PERSON as $day => [$min, $max]) {
            $this->assertSame($min * 100, (int) $byDay[$day]->sum('cost_min_minor'), "min for {$day}");
            $this->assertSame($max * 100, (int) $byDay[$day]->sum('cost_max_minor'), "max for {$day}");
        }
        $items = $this->trip()->itineraryItems();
        $this->assertSame(846000, (int) $items->sum('cost_min_minor'));
        $this->assertSame(1370000, (int) $this->trip()->itineraryItems()->sum('cost_max_minor'));
    }

    public function test_reseeding_is_safe_and_never_overwrites_existing_data(): void
    {
        $existing = User::factory()->create(['email' => SiquijorTripSeeder::ORGANIZER_EMAIL, 'password' => 'my-own-secret']);

        $this->seed(SiquijorTripSeeder::class);
        $edited = $this->trip()->itineraryItems()->orderBy('starts_at')->first();
        $edited->update(['title' => 'Renamed by organizer']);
        $this->seed(SiquijorTripSeeder::class);

        $this->assertSame(1, User::where('email', SiquijorTripSeeder::ORGANIZER_EMAIL)->count());
        $this->assertTrue(Hash::check('my-own-secret', $existing->fresh()->password));
        $this->assertSame(1, Trip::where('name', SiquijorTripSeeder::TRIP_NAME)->count());
        $this->assertSame(43, $this->trip()->itineraryItems()->count());
        $this->assertSame('Renamed by organizer', $edited->fresh()->title);
        $this->assertSame(1, $this->trip()->members()->count());
    }
}
