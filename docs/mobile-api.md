# BarKadaKaskas mobile API v1

Base URL: `https://YOUR_SERVER/api/v1`. Local emulator/device URLs must resolve
to the Laravel host (a physical phone cannot resolve the desktop Herd `.test`
hostname by default). Production always uses HTTPS. Send JSON and
`Accept: application/json`; uploads use multipart. Bearer authentication:
`Authorization: Bearer <token>`. No session cookies or CSRF tokens are required.

All IDs are integers. Money is integer minor units (PHP centavos). Dates are
`YYYY-MM-DD`; response timestamps are UTC ISO-8601. Submitted itinerary local
times (`YYYY-MM-DDTHH:mm`) are interpreted in the trip's IANA timezone; explicit
offset timestamps are accepted too. Nullable fields must remain nullable.

## Authentication

| Method | Path | Body / response |
|---|---|---|
| POST | `/auth/register` | `{name,email,password,password_confirmation,device_name}` → 201 `{token,user}` |
| POST | `/auth/login` | `{email,password,device_name}` → 200 `{token,user}` |
| GET | `/auth/me` | `{user:{id,name,email}}` |
| POST | `/auth/logout` | Revoke current device token → 204 |

Tokens expire after 30 days; sign in again after 401. Store in Android Keystore
or iOS Keychain. Never log passwords/tokens. `device_name` is a user-readable
string (max 120), optional, defaults to `Mobile app`.

## Trips and invitations

| Method | Path | Body / response |
|---|---|---|
| GET | `/trips` | `{data:[TripSummary]}`; only active memberships |
| POST | `/trips` | `{name,destination,description?,starts_on,ends_on,timezone,currency_code}` → 201 `{data:TripSummary}` |
| GET | `/trips/{trip}` | `{data:{trip,categories,balances,expense_shares,current_member_id,is_organizer}}` |
| POST | `/trips/{trip}/invitations` | Organizer only → 201 `{data:{token,url,expires_at}}` |
| GET | `/invitations/{token}` | Public, limited preview → `{data:{organizer_name,trip_name,destination,starts_on,ends_on}}` |
| POST | `/invitations/{token}/join` | Authenticated → `{data:{trip_id}}` |

TripSummary: `id,name,destination,description,starts_on,ends_on,timezone,
currency_code,organizer_user_id,members_count` (count supplied by list).
Trip detail `trip` additionally includes `members`, `itinerary_items`,
`expenses`, `settlements`. Members: `id,user_id,status,user:{id,name}`. No
member email or Google API keys are exposed. Category: `id,name` (other model
metadata may be present). Balances: `{member_id,name,net_minor}` (positive
receive, negative owe). `expense_shares` is an object keyed by expense ID:
`{member_id,share_minor,covered_minor,remaining_minor}[]`.

## Itinerary

POST `/trips/{trip}/itinerary` → 201 `{data:ItineraryItem}`.
PATCH `/trips/{trip}/itinerary/{item}` → 200 `{data:ItineraryItem}`.
Organizer only. Both take a full form:

```json
{"title":"Lunch","location":"San Juan, Siquijor, Philippines","description":"Meet at noon","starts_at":"2026-11-12T12:00","ends_at":"2026-11-12T13:00","transport_mode":"car","latitude":null,"longitude":null,"cost_min_minor":10000,"cost_max_minor":20000,"cost_note":"Per person"}
```

Transport values: `car,van,taxi,motorcycle,tricycle,walking,bicycle,transit,ferry,
flight,other` or null. Coordinates must be paired; costs must be paired and max
>= min. Omitted/cleared location or coordinates reset pins. Response includes
`id,trip_id,title,location,description,starts_at,ends_at,timezone,transport_mode,
latitude,longitude,cost_min_minor,cost_max_minor,cost_note`; coordinates may
serialize as decimal strings. Use external Google Maps directions links for
native navigation; web browser Maps keys must not be embedded into native apps.

## Expenses

POST `/trips/{trip}/expenses` → 201 `{data:Expense}` (any active member).
PATCH `/trips/{trip}/expenses/{expense}` → 200 `{data:Expense}` (organizer).
Expense edits require full form plus current `version`. Keep recorded payments
unchanged when editing. Closed/cancelled expenses cannot be edited.

```json
{"title":"Dinner","merchant":"Cafe","incurred_at":"2026-11-12","category_id":1,"total_minor":10000,"split_method":"equal","payments":[{"trip_member_id":1,"amount_minor":10000}],"allocations":[{"trip_member_id":1,"weight":1},{"trip_member_id":2,"weight":1}],"items":[]}
```

Split methods: `equal,exact,percentage,shares,itemized`. Exact weights are minor
units; percentage weights are basis points totaling 10000; shares are relative
integer weights. Itemized items: `{description,amount_minor,member_ids:[1,2]}`;
item totals must equal expense total. All three arrays are required even empty.
Server calculates allocations; never send calculated shares as authoritative.
Expense response includes `id,trip_id,title,merchant,incurred_at,category_id,
currency_code,total_minor,split_method,status,version,cancellation_reason` and
`category,items,allocations,payments,receipts`.

| Method | Path suffix after `/trips/{trip}/expenses/{expense}` | Body |
|---|---|---|
| PATCH | `/status` | `{status,version,reason?,void_payments?}` |
| POST | `/payments` | `{trip_member_id,amount_minor,version}` |
| POST | `/shares/paid` | `{member_id,version,settlement_id?,new_payment?}` |
| POST | `/receipts` | multipart field `receipt`, JPG/PNG/PDF <=10MB → 201 `{data:{id,processing_status}}` |

Other mutations return 200 `{data:Expense}`. Status values `draft,open,completed,
closed,cancelled`; lifecycle guards decide allowed transitions. Complete means
fully funded merchant bill, not all member debts repaid. Cancelling paid bills
requires reason and `void_payments:true`. Mark-share-paid is allowed only to the
organizer or covering payer; may require selecting a matching general settlement
or explicitly `new_payment:true` to avoid double counting. Refresh trip detail
after mutations for updated balances, shares, and versions. Receipt downloads:
GET `/receipts/{receipt}` authenticated binary download; never public paths.

## Reimbursements

POST `/trips/{trip}/settlements`: `{to_member_id,amount_minor,payment_method}`
→ 201 `{data:Settlement}`. Sender is inferred from token, never client supplied.
Methods `cash,gcash,bank_transfer,other`. POST
`/trips/{trip}/settlements/{settlement}/confirm` → 200 `{data:Settlement}`;
recipient only. Settlement includes `id,expense_id` (nullable), `from_member_id,
to_member_id,amount_minor,currency_code,payment_method,status,paid_at,confirmed_at`.
Pending settlements do not affect balances. Repeating confirmation is safe.

## Errors

Always JSON, including missing Accept header: 401 unauthenticated/expired,
403 forbidden, 404 missing or wrong parent, 410 expired/revoked invitation,
422 validation (including stale expense version), 429 throttled.
Format `{message,errors?:{field:[message,...]}}`. Display validation messages,
preserve form input, refresh on stale-version errors. Auth endpoints have stricter
rate limits; honor `Retry-After`. Do not automatically retry financial writes
after ambiguous network failures: refresh first to avoid duplicates.

Implementation reference: https://github.com/laravel/docs/blob/13.x/sanctum.md
