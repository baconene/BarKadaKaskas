# Pricing and cost model

Why Premium costs what it does, and why the monthly allowances are the size they are. The allowances live in
`config/services.php` (`services.premium.allowances`) and can be changed with environment variables.

## Plans

| Plan | Price | Who |
|---|---|---|
| Free | ₱0 | Everyone on a trip |
| Premium subscription | ₱1,450 every 30 days, saved card, renews until cancelled | Organizers who want AI |
| Premium trial | Free for 1 day (`PREMIUM_TRIAL_DAYS`), card required (checked with a ₱290 ≈ US$5 hold that is released, `PREMIUM_VERIFY_MINOR`), once per person | New subscribers |
| Premium one-time | ₱1,450 for 30 days via PayMongo Checkout (GCash, Maya, GrabPay, card), no renewal | People without a card |

## What we keep per subscriber

- ₱1,450 minus PayMongo's card fee (about 3.5% + ₱15 ≈ ₱66) ≈ **₱1,384 ≈ US$23.90** at ₱58 per US$.

## Worst case: a subscriber who uses every allowance

Unit costs are from the Google price sheet (`Pricing_for_BarkadaKaskas.csv`), **after** the monthly free tiers run out,
and with the expensive model (Gemini 4 Argon: $4 per million input tokens, $20 per million output tokens; Nano Banana 2.1
images at $30 per million output tokens). Token counts are estimates.

| Allowance (per month) | Premium | Estimated cost per use | Worst case |
|---|---|---|---|
| AI trip planner (`ai_plan`) | 30 | ~$0.18 (≈5k tokens in, 8k out) | $5.40 |
| AI receipt scans (`receipt_scan`) | 60 | ~$0.03 | $1.80 |
| AI photo edits and covers (`ai_image`) | 20 | ~$0.045 (≈1,300 image tokens) | $0.90 |
| AI tips, day prep, budget (`ai_insight`, cached answers are free) | 150 | ~$0.024 | $3.60 |
| In-app navigation (`navigation`) | 100 | $0.025 (Navigation SDK) | $2.50 |
| Place photo lookups (`place_photos`) | 100 | ~$0.046 (1 Text Search Pro + 2 photos) | $4.60 |
| **Total** | | | **≈ $18.80 (≈ 79% of what we keep)** |

So even the heaviest possible subscriber doesn't cost more than they pay. In practice it is far lower:

- Most subscribers use a fraction of the allowances.
- Google's monthly free tiers (1,000 navigations, 1,000 photos, 5,000 text searches, 10,000 routes and map loads) are
  shared by all users and absorb most traffic at the current scale.
- On `gemini-2.5-flash` (the default in `.env.example`) the AI rows cost a small fraction of the Argon figures.

## Trial and free users

- **Trial** (1 day): 5 plans, 5 receipts, 3 image edits, 10 insights, 10 navigations, 10 photo lookups:
  worst case ≈ $2.10 per trial that never converts.
- **Free**: 5 navigations and 10 photo lookups a month: worst case ≈ $0.60 per active free user. When they run out,
  navigation opens Google Maps (free for us) and photos come from Wikimedia Commons only.

## Other cost controls in the code

- Routes API results are cached per journey (`resources/js/lib/routeCache.ts`, Android `TripMap.kt`).
- Place photos: only 2 Google photos per place, topped up from Wikimedia; results cached for 7 days.
- Web place search asks only for Essentials fields (no `displayName`).
- AI tips, day prep and budget answers are cached for 30 days; only new answers use the allowance.

## Also set in Google Cloud Console

- A daily quota on each Maps API and on Vertex AI, so a bug or leaked key can't run up the bill.
- Budget alerts at 50%, 90% and 100% of the monthly budget.

## If prices change

Re-check this table when Google's prices, the model (`VERTEX_GEMINI_MODEL`) or the Premium price change. Keep the
worst case under about 80% of what we keep per subscriber.
