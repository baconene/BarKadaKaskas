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

`GET /trips` leaves out hidden trips and returns `meta.hidden_count`; `GET /trips?hidden=1` lists only the hidden ones.
A cancelled trip stays viewable. Itinerary changes, invitations, new expenses and expense edits return 403 with a
message; expense status changes, merchant payments, share confirmations and settlements still work so people can settle up.

| Method | Path | Body / response |
|---|---|---|
| GET | `/trips` | `{data:[TripSummary]}`; only active memberships |
| POST | `/trips` | `{name,destination,description?,starts_on,ends_on,timezone,currency_code}` → 201 `{data:TripSummary}` |
| GET | `/trips/{trip}` | `{data:{trip,categories,balances,dues,expense_shares,current_member_id,is_organizer}}`. `dues[]` is `{member_id,owes_minor,owed_minor}`: still to pay for one's own shares, and still to be paid back by others, on recorded bills |
| POST | `/trips/{trip}/invitations` | Organizer only → 201 `{data:{token,url,expires_at}}` |
| PATCH | `/trips/{trip}` | Organizer only, not cancelled. Same body as create; `currency_code` cannot change once expenses exist (422) |
| POST | `/trips/{trip}/cover` | Organizer only. Multipart `cover` (jpg/png/webp, ≤ 8 MB, ≥ 200×120) → `{data:Trip}` with `cover_url` |
| DELETE | `/trips/{trip}/cover` | Organizer only. Removes the cover photo |
| POST | `/trips/{trip}/cancel` | Organizer only. Body `{reason?, hide?}`. Sets `status: "cancelled"`; `hide: true` also sets `hidden_at`. 422 if already cancelled |
| POST | `/trips/{trip}/reopen` | Organizer only. Back to `planning`, clears `cancelled_at`, `cancellation_reason`, `hidden_at` |
| PATCH | `/trips/{trip}/visibility` | Organizer only. Body `{hidden}`. Only a cancelled trip can be hidden (422 otherwise) |
| GET | `/invitations/{token}` | Public, limited preview → `{data:{organizer_name,trip_name,destination,starts_on,ends_on}}` |
| POST | `/invitations/{token}/join` | Authenticated → `{data:{trip_id}}` |

TripSummary: `id,name,destination,description,starts_on,ends_on,timezone,
currency_code,organizer_user_id,members_count,status,cancellation_reason,hidden_at,cover_url`.
`cover_url` is a plain image URL (no auth header needed; the file name is unguessable) (count supplied by list).
Trip detail `trip` additionally includes `members`, `itinerary_items`,
`expenses`, `settlements`. Members: `id,user_id,status,user:{id,name}`. No
member email or Google API keys are exposed. Category: `id,name` (other model
metadata may be present). Balances: `{member_id,name,net_minor}` (positive
receive, negative owe). `expense_shares` is an object keyed by expense ID:
`{member_id,share_minor,covered_minor,remaining_minor}[]`.

## Itinerary

POST `/trips/{trip}/itinerary` → 201 `{data:ItineraryItem}`.
PATCH `/trips/{trip}/itinerary/{item}` → 200 `{data:ItineraryItem}`.
PATCH `/trips/{trip}/itinerary/{item}/done` `{done:boolean}` → 200 `{data:ItineraryItem}`. Any active member may tick an activity off or undo it; `completed_at` and `completed_by_user_id` are shared by everyone on the trip.
POST `/trips/{trip}/itinerary/suggestions` `{prompt}` → 200 `{data:[{title,location,description,starts_at,ends_at,transport_mode,cost_min_minor,cost_max_minor,cost_note}]}`. Organizer only. AI (Gemini on Vertex AI) drafts activities inside the trip dates; times are local `YYYY-MM-DDTHH:MM`, costs per person in minor units. Nothing is saved; can take up to a minute; 503 when the planner isn't configured (trip detail `ai_planner` says whether it is).
POST `/trips/{trip}/itinerary/batch` `{items:[…same fields…]}` → 201 `{data:[ItineraryItem]}`. Organizer only. Saves the approved activities in one go.

Day highlight: GET `/trips/{trip}/highlights[?date=YYYY-MM-DD]` (default today in the trip's time zone) → `{data:{date,day_number,day_count,title,subtitle,cover_url,base_photo_url,music_url,cover_duration,outro_duration,slides:[{time,title,location,photo_url,caption,duration}],stops,days}}`. Slides follow the itinerary in order with that day's posted photos (best by hearts and comments, up to 12); total about 30 s. POST `/trips/{trip}/highlights/cover?date=` makes the AI cover from the day's best photo (one AI edit; shared with the trip) → `{data:{cover_url}}`.

AI photo edits: POST `/photos/restyle` multipart `photo, style? | prompt?, subject` (`photo` for trip photos). Styles: art styles (`cartoon`, `anime`, `watercolor`, `3d`, `pop-art`, `sketch`, `golden-hour`, `postcard`) and edit templates that keep the photo real (`enhance`, `brighten`, `blue-sky`, `sunset-sky`, `remove-people`, `tropical`); a `prompt` on a trip photo is an edit request. → `{data:{image:"data:<mime>;base64,…"}, ai:{limit,remaining,resets_in}}`. Limit: 3 successful edits per traveller every 4 hours; over it → 429 with `message` and `ai`. GET `/photos/ai-quota` → `{data:{limit,remaining,resets_in,templates:{edits,styles}}}`.

Alerts (others' activity on your trips: `comment`, `post`, `expense`, `itinerary`): GET `/alerts[?after=<id>]` → `{data:[{id,type,title,body,trip_id,trip_name,data,read,created_at}], unread_count}` (newest first, up to 50); GET `/alerts/unread-count`; POST `/alerts/read` `{ids?}` (none = all) → `{unread_count}`. The Android app checks `?after=` in the background about every 15 minutes and shows phone notifications.

Photo comments: GET `/trips/{trip}/posts/{post}/comments` → `{data:[{id,body,created_at,author:{member_id,name,avatar_url},can_delete}]}` (oldest first). POST `{body}` (≤1000 chars) → 201 `{data:Comment, comments_count}`. DELETE `/trips/{trip}/posts/{post}/comments/{comment}` → `{comments_count}` (comment author, post author or organizer). Posts include `comments_count`.

Traveller documents (boarding pass, ID, bank QR, booking, other): GET `/trips/{trip}/documents` → `{data:[{id,member_id,type,label,visibility,mime_type,size,is_image,mine,file_path}]}` lists what you may see (`visibility` `trip` = everyone on the trip, `private` = owner and organizer). POST multipart `type,label?,visibility?,file` (JPG/PNG/WebP/PDF ≤10 MB; a bank QR defaults to `trip`, others to `private`) → 201. PATCH/DELETE `/trips/{trip}/documents/{id}` (owner only). The file is at `/api/v1` + `file_path`, sent with the bearer token; it is never public.
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
| PUT | `/payers` | `{version,payments:[{trip_member_id,amount_minor}]}`. Organizer replaces who paid the merchant (empty = unpaid); shares unchanged. Cannot exceed the total; a completed expense stays paid in full; locked once reimbursements are recorded |
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

## App updates (Android)

`GET /api/v1/app/android` (no login) returns the newest Android build from the repository's latest GitHub release
(cached 10 minutes): `version_code`, `version_name`, `apk_url`, `size` (bytes), `notes` (the release's commit message
without trailers), `published_at`, and `min_version_code` (from `ANDROID_MIN_VERSION`; builds below it must update).
The app checks this when it opens and about every 15 minutes in the background (falling back to GitHub directly),
notifies once per version, and downloads and installs the APK from inside the app.

## Between you and each traveller

`GET /api/v1/trips/{trip}` includes `between`: for the signed-in traveller, one entry per other traveller with
`net_minor` (> 0 they owe you, < 0 you owe them) and `lines` (`type` expense|payment, `label`, `date`, `detail`,
`amount_minor` with the same sign, `pending`). On each recorded bill, whoever paid the merchant more than their share
fronted money for those who paid less, split in proportion to what each fronted; money not yet paid to the merchant is
not counted. Confirmed repayments between the two reduce it; pending ones are listed but not counted.

### AI planner: moving planned activities

`POST /trips/{trip}/itinerary/suggestions` also returns `changes`: new times for activities already planned
(`item_id`, `title`, `from_starts_at`, `from_ends_at`, `starts_at`, `ends_at`, `reason`), for requests like "move the
busy spots to tomorrow". `POST /trips/{trip}/itinerary/batch` accepts `changes` (`item_id`, `starts_at`, `ends_at`)
alongside or instead of `items`. The model has 50 seconds; slower answers return 504 with a message.

## Photos of a stop's place

`GET /api/v1/trips/{trip}/itinerary/{item}/photos` returns up to 6 web photos of the place: `url`, `credit` (who to
credit, if known) and `source` (`Google` or `Wikimedia Commons`). Google Places photos (Places API (New), with
`GOOGLE_MAPS_BROWSER_KEY`) come first; Wikimedia Commons tops them up when Google has fewer than five. Only links are
returned (no files are stored); results are cached for a week per place.

## Things to do at a stop's place

`GET /api/v1/trips/{trip}/itinerary/{item}/tips` returns about five recommended things to do there (`title`,
`detail` — a one-line tip), written by Gemini on Vertex AI and cached per place for 30 days. Empty when the AI isn't
set up or can't answer.

## Prep for the day

`GET /api/v1/trips/{trip}/itinerary-day?date=YYYY-MM-DD` (default today in the trip's time zone) returns `date`,
`stops`, `summary`, `outfit` (what to wear), `bring` (things and equipment), `tips` and `available`, written by Gemini
from that day's itinerary. Cached per day and plan, so it is rewritten when the day's activities change; the lists
are empty and `summary` null when the day has no activities or the AI isn't available.
