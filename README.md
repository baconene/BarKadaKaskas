# BarKadaKaskas

Travel planning and fair expense sharing for your barkada. Laravel 13, Vue 3, Inertia, TypeScript and Tailwind.

## Start locally

Requires PHP 8.4.1+, Composer and Node 22.

```sh
composer install
npm ci
cp .env.example .env
php artisan key:generate
```

Create an empty `database/database.sqlite` file, then:

```sh
php artisan migrate --seed
npm run build
php artisan serve
```

For frontend development, use `composer run dev`.

## Verify

```sh
php vendor/phpunit/phpunit/phpunit
npx vue-tsc --noEmit
npm run build
```

See [implementation and Forge deployment notes](docs/implementation.md) for model design, completed features, remaining phases and configuration.

Expenses support open, completed (fully paid to merchant), closed (locked), and cancelled (unpaid, reason required). Closing an expense preserves member reimbursement balances. Money is stored in integer minor units.

The iOS and Android directories reserve the future native client projects. They are not compiled apps yet.
