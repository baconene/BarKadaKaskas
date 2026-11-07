# BarKadaKaskas implementation

Laravel 13 / Vue 3 / Inertia 2. All amounts use integer minor units. The initial supported currencies have two decimal places. Each trip has one currency, its own membership boundary, and one organizer.

## Delivered web foundation

- Registration, login, password reset and account settings from the official Vue starter kit.
- Multiple private trips, expiring invitation links, organizer-owned itinerary in the trip time zone.
- Equal, exact, percentage, shares and itemized splits. Itemized lines currently split equally among selected travelers; reviewed line totals must include applicable tax/discount.
- Multiple merchant payers and subsequent payment entry.
- Organizer-controlled expense lifecycle with optimistic version checks and row locking.
- Organizer editing of open/completed expenses and assigned shares, with live split previews. Closed/cancelled expenses remain locked. Existing merchant payments are preserved and changes are audited.
- Public animated invitation landing page with organizer/trip/date details, login/signup return flow, and expired-link handling. Trip destinations and itinerary locations open Google Maps.
- Private receipt upload/download and manual entry.
- Derived trip balances, reimbursement recording and recipient confirmation.
- All planned domain models and migration tables, including the future extraction/proposal/adjustment entities.

## Expense lifecycle

`draft -> open -> completed -> closed`

`draft/open -> cancelled` only when no payments have been recorded, with a required reason.

Completed means the merchant is fully paid and the allocation equals the expense total. Closed locks the record; it does not erase member reimbursement obligations. Cancelled is terminal and excluded from spending/balances. A paid expense cannot be cancelled until a refund/reversal workflow is implemented. Never delete recorded payments to simulate a refund.

Open expenses are provisional and excluded from balances even if partially paid. The UI labels the balance summary as completed/closed expenses only. Completion and closure retain their timestamps. Status changes and payment additions have audit records.

## Models

User, Trip, TripMember, TripInvitation, ItineraryItem, ItineraryParticipant, ExpenseCategory, Expense, ExpensePayment, ExpenseItem, ExpenseAdjustment, ExpenseAllocation, ExpenseAllocationProposal, Receipt, ReceiptExtraction, Settlement, ActivityLog.

TripMember is the reference for payers, recipients and allocated shares. HTTP writes validate that every referenced member and category belongs to the expense's trip. Invitation tokens are stored as hashes. Membership is unique per trip/user. Financial history restricts deleting accounts that have participated in trips.

## Next phases (not yet delivered)

- Google Cloud Document AI Expense Parser integration, queued extraction, review/correction screen and safe retry handling. Uploads currently say manual review; no fake extraction is performed.
- Member proposals/claims and organizer approval; direct organizer editing is available now.
- Explicit receipt adjustments and custom categories in the UI; schema is ready.
- Refund/reversal ledger, trip ownership transfer, membership changes and trip archival UI.
- Versioned Sanctum API, mobile apps, push notifications and offline synchronization.
- Foreign currency conversion and public trip discovery.

Native directories are reserved at `ios/BarKadaKaskas` and `android/BarKadaKaskas`.

## Google receipt reading

Chosen direction: Google Cloud Document AI Expense Parser (EXPENSE_PROCESSOR). It can return receipt date, currency, total and line-item fields. Use human-reviewed normalized results; extraction must never silently finalize an expense or assume who consumed an item.

Before integration can be enabled, provision a Google Cloud project with billing and the Document AI API, create an Expense Parser processor in the selected region, and grant the server identity Document AI API User access. Keep the service-account credentials outside the repository and public web root. Configure the project ID, location, processor ID and credential path through Forge. Do not paste private keys into chat.

Reference: https://docs.cloud.google.com/document-ai/docs/setup and https://docs.cloud.google.com/document-ai/docs/process-documents-client-libraries . Live OCR testing requires that configuration and sample receipts.

## Deployment

Use PHP 8.4.1+ (the resolved dependency set needs it), Node 22 and MySQL on Forge. Point the document root at `public`. Configure HTTPS, `APP_URL`, `APP_ENV=production`, `APP_DEBUG=false`, database, mail and a unique `APP_KEY` in Forge's environment editor. Do not commit secrets or uploaded receipts.

Deploy commands after checkout:

```sh
composer install --no-dev --no-interaction --prefer-dist --optimize-autoloader
npm ci
npm run build
php artisan migrate --force
php artisan db:seed --force
php artisan optimize
php artisan queue:restart
```

The category seeder is idempotent and does not create demo accounts. Back up the database and private `storage/app/private` files. Use a persistent storage directory across releases. Configure a queue worker before enabling OCR and configure mail before expecting password-reset email delivery. Native mobile compilation is separate from Forge's web deployment.

Local development defaults to SQLite. Copy `.env.example` to `.env`, generate the key, create `database/database.sqlite`, then migrate/seed. Run `composer run dev`. Verification: `php vendor/phpunit/phpunit/phpunit`, `npx vue-tsc --noEmit`, `npm run build`.
