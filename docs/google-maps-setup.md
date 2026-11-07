# Google Maps itinerary routes

The itinerary connects consecutive stops in schedule order (start time, then ID).
Each stop's transport describes travel **from the preceding stop**. The first
stop is the starting point. Add full addresses including town and country.
Editing a location, time, or transport recalculates the map when saved.

## Enable the interactive map

1. Create a Google Cloud project and attach a billing account.
2. Enable **Maps JavaScript API**, **Routes API**, **Geocoding API**, and
   **Places API (New)** in that project.
3. Create a browser API key. Set application restrictions to **Websites (HTTP
   referrers)**, allowing `http://barkadakaskas.test/*` for Herd and your actual
   production HTTPS domain. Restrict the key to those four APIs. This key is
   intentionally visible to the browser; do not reuse a server or OCR key.
4. Add to your local `.env` and Forge environment (do not commit the key):

   ```dotenv
   GOOGLE_MAPS_BROWSER_KEY=your_restricted_browser_key
   GOOGLE_MAPS_MAP_ID=DEMO_MAP_ID
   ```

   `DEMO_MAP_ID` works for development. Create a JavaScript map ID in Google
   Cloud for production and replace it in Forge.
5. Run `php artisan config:clear` locally. On Forge run your usual deployment,
   including `php artisan migrate --force`, `npm ci && npm run build`, and
   `php artisan config:cache` after updating the environment.
6. Refresh a trip, open **Itinerary**, and add at least two full locations.
   Select transport on the second stop. The map should draw the connecting
   route, numbered stops, distance, and estimated duration. Test a second leg
   with another mode and edit a location to verify the redraw.

Google bills Maps usage according to your project plan. Set budget alerts and
API quotas in Cloud Console. One Maps load and one Routes request per supported
leg may occur when the itinerary opens or changes. Addresses are sent to Google
for routing; no live GPS tracking is performed.

## Behavior and limits

- Car, van, taxi, and tricycle use driving routes; tricycle is explicitly a
  driving estimate, not tricycle-specific road eligibility.
- Motorcycle, walking, bicycle, and public transit use their respective modes
  where Google supports them. Transit estimates use Google's current default
  departure time, not future itinerary schedules.
- Ferry, flight, and other transport have no Google route. Their stops are
  geocoded and joined by a direct line (dashed for flights, dotted for ferries
  and other transport) labelled with the straight-line distance. This needs the
  Geocoding API; without it the map shows a notice and those legs stay unmapped.
- If a road, walking, or transit route cannot be found (for example across
  water), the leg falls back to a direct line and says so.
- Missing locations or transport choices leave a gap in the mapped path.
- Route failures stay visible per leg; other available legs still render.
  Google route warnings are displayed with the corresponding leg.
- Without a key, all itinerary editing and per-leg Google Maps links still work.
- The itinerary **Location** field searches Google Places as you type. Picking a
  suggestion saves the place's exact coordinates with the stop, so the map pins
  it without a geocoding lookup. Typing over a picked place clears its pin.
  Without Places API (New) the field works as a plain address input.
- No GPS tracking is performed.

Official setup: https://developers.google.com/maps/documentation/javascript/routes/start
Route drawing: https://developers.google.com/maps/documentation/javascript/routes/get-a-route
