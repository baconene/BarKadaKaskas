# Seeding the Siquijor trip

`Database\Seeders\SiquijorTripSeeder` creates the organizer account, the
"Siquijor Barkada Trip" (Nov 11–17, 2026), and its 43-stop itinerary with
pinned Google Maps locations and per-person budget estimates.

## Run it (local or Forge)

```bash
php artisan migrate --force
php artisan db:seed --class=SiquijorTripSeeder --force
```

On Forge, run these from the site's **Commands** tab (or SSH into the current
release). The seeder is safe to run more than once:

- The organizer account (`john.adrian.bacon@gmail.com`) is only created if it
  does not exist. An existing account keeps its name and password.
- The trip is only created if this organizer has no trip with that name.
- The itinerary is only added when the trip has no stops yet, so edits made in
  the app are never overwritten.

## Organizer password

A newly created organizer account gets the password from
`SEED_ORGANIZER_PASSWORD`, or `password` when it is not set. On production, set
`SEED_ORGANIZER_PASSWORD` in the Forge environment before seeding, or log in and
change the password right after seeding.

## Budget data

The source plan prices everything for 5 travellers. Each stop stores a
per-person range (group amount ÷ 5) on the stop where the money is spent, with
a breakdown in the stop details. A day's "meals" figure is split 20% breakfast,
35% lunch, 45% dinner. Daily and trip totals match the plan exactly
(PHP 8,460–13,700 per person). Flights, the Isla Cove stay, and shopping are not
included.
