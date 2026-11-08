# Receipt scanning (Gemini on Vertex AI)

**Scan a receipt** on the new-expense form (web, Android, iOS) sends the photo to
`POST /trips/{trip}/receipt-scans`. The server asks Gemini for strict JSON
(merchant, date, items with line totals, service charge, tax added on top,
discount, total, category) and turns it into an expense draft:

- each receipt line becomes an itemized-split item, initially shared by everyone;
- service charge and add-on tax become their own lines; discounts are spread
  over the items; small rounding gaps go to the largest line, so the lines add
  up to the total exactly as itemized splits require;
- bigger gaps, a foreign currency or unclear photos come back as `warnings`
  that the form shows. Nothing is saved until the traveller taps Save.

Saving the expense with `receipt_scan_id` attaches the photo as the expense's
receipt (`processing_status: extracted`) and records the reading in
`receipt_extractions`. Scans never saved are deleted after two days by
`receipts:prune-scans` (needs the Laravel scheduler running).

## Setup

1. In Google Cloud, pick (or create) a project with billing. Vertex AI is now
   called **Agent Platform** in the console. Easiest: tap **Get API key** in its
   sidebar (express mode) and set `VERTEX_API_KEY`; skip to step 4. Otherwise
   enable only the **Agent Platform API** (`aiplatform.googleapis.com`) and the
   **IAM API**; the "Enable APIs" bundle dialog is not needed.
2. Create a service account with the **Vertex AI User** role and create a JSON
   key for it.
3. In Forge → Environment, add the key in one of these forms (never commit it):

   ```dotenv
   # the whole JSON on one line, base64 of it, or a path on the server
   VERTEX_CREDENTIALS=base64-of-the-json-key
   VERTEX_LOCATION=global            # or e.g. asia-southeast1
   VERTEX_GEMINI_MODEL=gemini-2.5-flash  # use the exact model ID of a current Flash / Flash Lite model
   ```

   `VERTEX_PROJECT_ID` is read from the key; set it only to use another project.
   Alternatively, use a Vertex AI **express mode** API key: `VERTEX_API_KEY=...`.
4. Deploy, run `php artisan migrate --force` and `php artisan config:cache`,
   and make sure the scheduler runs (`* * * * * php artisan schedule:run`).

Without a key the scan button answers "Receipt scanning is not set up yet" and
expenses can still be entered by hand. Upstream errors are logged with Google's
error status (e.g. `PERMISSION_DENIED` when the role or API is missing).

## Cost and privacy

Each scan is one Gemini request with one image (billed by tokens; a receipt is
typically a fraction of a cent on Flash models). Scans are limited to 15 per
minute per user. Photos are stored on the private disk and only sent to Google
for reading; receipts stay visible only to trip members.
