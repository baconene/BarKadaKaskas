<?php

namespace Database\Seeders;

use App\Models\Trip;
use App\Models\User;
use Carbon\CarbonImmutable;
use Illuminate\Database\Seeder;
use Illuminate\Support\Facades\DB;

/**
 * Siquijor barkada trip (Nov 11–17, 2026), organised by John.
 *
 * Safe for production: it only creates what is missing and never overwrites an
 * existing account, trip, or itinerary. Run with:
 *   php artisan db:seed --class=SiquijorTripSeeder --force
 *
 * Costs come from the trip plan, which prices everything for a group of 5.
 * They are stored per person (group amount / 5) on the stop where the money is
 * spent. A day's "meals" figure is split 20% breakfast, 35% lunch, 45% dinner.
 * Each day's per-person total matches the plan exactly. Flights and the Isla
 * Cove stay are not included, as in the plan.
 */
class SiquijorTripSeeder extends Seeder
{
    public const ORGANIZER_EMAIL = 'john.adrian.bacon@gmail.com';
    public const TRIP_NAME = 'Siquijor Barkada Trip';
    public const GROUP_SIZE = 5;
    private const TIMEZONE = 'Asia/Manila';

    /** Places verified with Google Places: [label, latitude, longitude]. */
    private const PLACES = [
        'naia' => ['Ninoy Aquino International Airport, Pasay City, Metro Manila', 14.5122739, 121.0165080],
        'dgt_airport' => ['Dumaguete–Sibulan Airport, Sibulan, Negros Oriental', 9.3337352, 123.2959320],
        'dgt_port' => ['Port of Dumaguete City, Flores Ave, Dumaguete City, Negros Oriental', 9.3127689, 123.3109068],
        'sqj_port' => ['Siquijor Port, Siquijor', 9.2180135, 123.5123413],
        'isla_cove' => ['Isla Cove, Purok 1, Tubod, San Juan, Siquijor', 9.1451968, 123.5099511],
        'tambayanan' => ['Tambayanan Cafe-FaD Barn, San Juan, Siquijor', 9.1404390, 123.5144805],
        'tubod' => ['Tubod Marine Sanctuary, San Juan, Siquijor', 9.1415965, 123.5091028],
        'tagbalayon' => ['Tagbalayon Restaurant, San Juan, Siquijor', 9.1446588, 123.5097903],
        'capilay' => ['Capilay Spring Park, Siquijor Circumferential Rd, San Juan, Siquijor', 9.1587558, 123.4934397],
        'san_juan' => ['San Juan, Siquijor', 9.1587338, 123.4928197],
        'paliton' => ['Paliton Beach, San Juan, Siquijor', 9.1779155, 123.4615956],
        'dolce_amore' => ['Dolce Amore Italian Restaurant, Paliton, San Juan, Siquijor', 9.1785082, 123.4708111],
        'reas' => ["Rea's Kitchenette, Purok 3, San Juan, Siquijor", 9.1448135, 123.5097445],
        'cambugahay' => ['Cambugahay Falls, Lazi, Siquijor', 9.1398965, 123.6266693],
        'lazi_church' => ['Lazi Church and Convent, Lazi, Siquijor', 9.1274950, 123.6330041],
        'lazi' => ['Lazi, Siquijor', 9.1320743, 123.6063855],
        'balete' => ['Old Enchanted Balete Tree, Lazi, Siquijor', 9.1209150, 123.5753902],
        'baha_bar' => ["Baha Ba'r, Maite, San Juan, Siquijor", 9.1519646, 123.4994715],
        'chekesh' => ['Chekesh Casa, San Juan, Siquijor', 9.1449207, 123.5092466],
        'salagdoong' => ['Salagdoong Beach, Maria, Siquijor', 9.2131356, 123.6815554],
        'maria' => ['Maria, Siquijor', 9.1654580, 123.6511438],
        'luca_loko' => ['Luca Loko Restaurant, Maite, San Juan, Siquijor', 9.1542172, 123.4972914],
        'cantabon' => ['Cantabon Cave, Siquijor', 9.1863312, 123.5641383],
        'cocorico' => ['CocoRico Bar and Restaurant, San Juan, Siquijor', 9.1658931, 123.4845211],
        'gerrys' => ["Gerry's Grill, Rizal Boulevard, Dumaguete City", 9.3058896, 123.3101755],
        'rizal_blvd' => ['Rizal Boulevard, Dumaguete City, Negros Oriental', 9.3042398, 123.3106002],
        'casablanca' => ['Casablanca Restaurant, Rizal Boulevard cor. Noblefranca St, Dumaguete City', 9.3085043, 123.3094963],
        'dumaguete' => ['Dumaguete City, Negros Oriental', 9.2984086, 123.2924859],
    ];

    /**
     * Stops in schedule order: [start, until, title, place, transport, costs, notes].
     * Times are Asia/Manila; "until" may be "+1 HH:MM" for the next morning.
     * Costs are [label, group min, group max] in pesos for all 5 travellers.
     */
    private function stops(): array
    {
        $nightAtIslaCove = 'Isla Cove stay Nov 11–16 (accommodation is not included in the budget).';

        return [
            '2026-11-11' => [
                ['15:20', '15:20', 'Depart Manila — flight MNL → DGT', 'naia', null, [], 'Flight 15:20 → 16:50. Airfare is not included in the budget.'],
                ['16:50', '17:30', 'Arrive at Dumaguete Airport', 'dgt_airport', 'flight', [], null],
                ['18:00', '19:10', 'Dumaguete Port — wait for the evening ferry', 'dgt_port', 'taxi', [['Airport → port transfer', 600, 1250]], 'Transfer about 15–30 min.'],
                ['20:10', '20:20', 'Arrive at Siquijor Port', 'sqj_port', 'ferry', [['Ferry ticket', 1900, 3000]], "Target ferry 19:10, about 40–60 min crossing. Tentative — schedule and boarding cutoff are unconfirmed.\nIf the evening ferry is missed or cancelled, stay overnight in Dumaguete and cross on Nov 12 (extra lodging is not included)."],
                ['21:00', '+1 07:15', 'Check in at Isla Cove + dinner', 'isla_cove', 'van', [['Port → Isla Cove transfer', 600, 1250], ['Dinner', 1500, 2000]], "Transfer about 25–40 min.\n{$nightAtIslaCove}"],
            ],
            '2026-11-12' => [
                ['07:30', '08:45', 'Breakfast at Tambayanan Cafe-FaD Barn', 'tambayanan', 'walking', [['Breakfast', 700, 1100], ['Car rental (day)', 2500, 3750], ['Fuel', 300, 500]], null],
                ['09:00', '11:15', 'Snorkeling at Tubod Marine Sanctuary', 'tubod', 'van', [['Snorkeling and entrance fees', 500, 1000]], null],
                ['11:30', '12:45', 'Lunch at Tagbalayon Restaurant', 'tagbalayon', 'walking', [['Lunch', 1225, 1925]], null],
                ['13:00', '14:30', 'Swimming at Capilay Spring Park', 'capilay', 'van', [], null],
                ['14:30', '16:10', 'Rest and café hopping in San Juan', 'san_juan', 'walking', [], null],
                ['16:30', '18:15', 'Sunset and beach photos at Paliton Beach', 'paliton', 'van', [], null],
                ['18:30', '20:00', 'Dinner at Dolce Amore', 'dolce_amore', 'van', [['Dinner', 1575, 2475]], null],
                ['20:15', '+1 06:45', 'Back at Isla Cove for the night', 'isla_cove', 'van', [], null],
            ],
            '2026-11-13' => [
                ['07:00', '07:45', "Breakfast at Rea's Kitchenette", 'reas', 'walking', [['Breakfast', 700, 1100], ['Car rental (day)', 2500, 3750], ['Fuel', 400, 650]], null],
                ['08:45', '10:40', 'Cambugahay Falls — swimming, rope swings and photos', 'cambugahay', 'van', [['Entrance and activity fees', 750, 1500]], 'Drive about 45–60 min.'],
                ['11:00', '12:00', 'Lazi Church and Convent', 'lazi_church', 'van', [], null],
                ['12:00', '13:10', 'Lunch at a local Filipino restaurant in Lazi', 'lazi', 'van', [['Lunch', 1225, 1925]], null],
                ['13:30', '14:30', 'Balete Tree fish spa', 'balete', 'van', [], null],
                ['15:30', '18:15', 'Back to San Juan — free time at Isla Cove', 'isla_cove', 'van', [], 'Drive about 35–60 min.'],
                ['18:30', '20:00', "Dinner at Baha Ba'r", 'baha_bar', 'van', [['Dinner', 1575, 2475]], null],
                ['20:15', '+1 07:15', 'Back at Isla Cove for the night', 'isla_cove', 'van', [], null],
            ],
            '2026-11-14' => [
                ['07:30', '08:30', 'Breakfast at Chekesh Casa', 'chekesh', 'walking', [['Breakfast', 700, 1100], ['Car rental (day)', 2500, 3750], ['Fuel', 500, 850]], null],
                ['10:00', '12:00', 'Salagdoong Beach — swimming, cliff views and photos', 'salagdoong', 'van', [['Entrance and activity fees', 500, 1000]], "Drive about 70–90 min.\nCliff jumping only if officially permitted and safe. Substitute other coastal viewpoints if beach access is restricted."],
                ['12:00', '14:30', 'Lunch in Maria + coastal sightseeing', 'maria', 'van', [['Lunch', 1225, 1925]], null],
                ['16:15', '18:15', 'Relax at Isla Cove', 'isla_cove', 'van', [], 'Scenic drive back to San Juan, about 75–105 min.'],
                ['18:30', '20:00', 'Dinner at Luca Loko', 'luca_loko', 'van', [['Dinner', 1575, 2475]], null],
                ['20:15', '+1 07:45', 'Back at Isla Cove for the night', 'isla_cove', 'van', [], null],
            ],
            '2026-11-15' => [
                ['08:00', '09:00', 'Breakfast at Tambayanan Cafe-FaD Barn', 'tambayanan', 'walking', [['Breakfast', 700, 1100], ['Car rental (day)', 2500, 3750], ['Fuel', 300, 500]], null],
                ['09:45', '12:00', 'Guided tour of Cantabon Cave', 'cantabon', 'van', [['Cave guide and optional activities', 750, 1500]], "Drive about 30–45 min; tour about 2 hours.\nAlternative: skip the cave for a relaxed beach and café day, and adjust the activities budget."],
                ['13:00', '16:10', 'Lunch at roadside diners + massage, coffee and relaxation', 'san_juan', 'van', [['Lunch', 1225, 1925]], null],
                ['16:30', '18:15', 'Final Siquijor sunset at Paliton Beach', 'paliton', 'van', [], null],
                ['18:30', '20:00', 'Dinner at CocoRico', 'cocorico', 'van', [['Dinner', 1575, 2475]], null],
                ['20:15', '+1 07:00', 'Back at Isla Cove for the night', 'isla_cove', 'van', [], null],
            ],
            '2026-11-16' => [
                ['07:00', '08:00', 'Breakfast, pack and check out', 'isla_cove', null, [['Breakfast', 700, 1000]], null],
                ['08:40', '10:00', 'Siquijor Port — ferry to Dumaguete', 'sqj_port', 'van', [['Transfer to Siquijor Port', 250, 500]], 'Transfer about 25–40 min. Target ferry 10:00 — tentative, confirm the schedule.'],
                ['11:00', '11:40', 'Arrive at Dumaguete Port', 'dgt_port', 'ferry', [['Return ferry ticket', 1900, 3000]], 'Crossing about 40–60 min.'],
                ['12:00', '13:45', "Lunch at Gerry's Grill Dumaguete", 'gerrys', 'taxi', [['Port → city transfer', 250, 500], ['Lunch', 1225, 1750]], null],
                ['14:00', '15:45', 'Check in to Dumaguete hotel', 'dumaguete', 'taxi', [['Dumaguete hotel (1 night)', 3000, 6000]], 'Hotel not booked yet — the pin shows Dumaguete City centre. Stay in Dumaguete the night before the early flight.'],
                ['16:00', '17:45', 'Rizal Boulevard and souvenir shopping', 'rizal_blvd', 'taxi', [], 'Shopping is not included in the budget.'],
                ['18:00', '19:45', 'Dinner at Casablanca Restaurant', 'casablanca', 'walking', [['Dinner', 1575, 2250]], null],
                ['20:00', '+1 05:15', 'Back to the hotel to rest', 'dumaguete', 'taxi', [], 'Wake up 04:45 and pack for the 05:15 airport transfer.'],
            ],
            '2026-11-17' => [
                ['05:30', '07:20', 'Dumaguete Airport — flight DGT → MNL', 'dgt_airport', 'taxi', [['Airport transfer', 300, 500], ['Breakfast and snacks', 500, 1000]], 'Target arrival 05:30 for the 07:20 departure. Airfare is not included in the budget.'],
                ['08:50', '09:30', 'Arrive in Manila', 'naia', 'flight', [], null],
            ],
        ];
    }

    public function run(): void
    {
        DB::transaction(function () {
            $organizer = User::firstOrCreate(
                ['email' => self::ORGANIZER_EMAIL],
                ['name' => 'John Adrian Biticon', 'password' => config('services.seed.organizer_password'), 'email_verified_at' => now()],
            );

            $trip = Trip::firstOrCreate(
                ['organizer_user_id' => $organizer->id, 'name' => self::TRIP_NAME],
                [
                    'destination' => 'Siquijor, Philippines',
                    'description' => "Balanced-pace barkada trip for 5 with a rental 7-seater van. Staying at Isla Cove (Tubod, San Juan), Nov 11–16.\n"
                        ."Budget estimates are per person: PHP 8,460–13,700 (plus PHP 1,000–2,000 suggested contingency). "
                        .'Not included: flights, Isla Cove accommodation, shopping, an unexpected overnight stay from a ferry cancellation, and extra travellers beyond five.',
                    'starts_on' => '2026-11-11',
                    'ends_on' => '2026-11-17',
                    'timezone' => self::TIMEZONE,
                    'currency_code' => 'PHP',
                ],
            );
            $trip->members()->firstOrCreate(['user_id' => $organizer->id], ['status' => 'active', 'joined_at' => now()]);

            if ($trip->itineraryItems()->exists()) {
                $this->command?->info('Siquijor itinerary already exists; left unchanged.');
                return;
            }

            $order = 0;
            foreach ($this->stops() as $date => $stops) {
                foreach ($stops as [$start, $until, $title, $place, $transport, $costs, $notes]) {
                    [$location, $latitude, $longitude] = self::PLACES[$place];
                    $trip->itineraryItems()->create([
                        'created_by_user_id' => $organizer->id,
                        'title' => $title,
                        'location' => $location,
                        'latitude' => $latitude,
                        'longitude' => $longitude,
                        'transport_mode' => $transport,
                        'description' => $notes,
                        'starts_at' => $this->at($date, $start),
                        'ends_at' => $this->at($date, $until),
                        'timezone' => self::TIMEZONE,
                        'sort_order' => $order++,
                        ...$this->cost($costs),
                    ]);
                }
            }
            $this->command?->info("Seeded {$order} Siquijor itinerary stops for ".self::ORGANIZER_EMAIL.'.');
        });
    }

    private function at(string $date, string $time): CarbonImmutable
    {
        $day = CarbonImmutable::parse($date, self::TIMEZONE);
        if (str_starts_with($time, '+1 ')) { $day = $day->addDay(); $time = substr($time, 3); }
        return CarbonImmutable::parse($day->format('Y-m-d').' '.$time, self::TIMEZONE)->utc();
    }

    /** Per-person cost range in centavos plus a readable breakdown. */
    private function cost(array $costs): array
    {
        if (! $costs) return ['cost_min_minor' => null, 'cost_max_minor' => null, 'cost_note' => null];
        $perPerson = fn (int $group) => intdiv($group, self::GROUP_SIZE);
        $peso = fn (int $amount) => '₱'.number_format($amount);
        $note = collect($costs)->map(fn ($c) => "{$c[0]} {$peso($perPerson($c[1]))}–{$peso($perPerson($c[2]))}")->implode(' · ');
        return [
            'cost_min_minor' => array_sum(array_map(fn ($c) => $perPerson($c[1]), $costs)) * 100,
            'cost_max_minor' => array_sum(array_map(fn ($c) => $perPerson($c[2]), $costs)) * 100,
            'cost_note' => $note,
        ];
    }
}
