# What blablacar.hr actually does (Step 0 recon, 2026-11-11)

Recon was done on the real phone (Galaxy A53, Android 16) with a debug WebView that hooks `fetch` and logs/saves
traffic. Everything below was observed, not guessed.

## Bot protection

* `curl` / any non-browser client gets **403 + DataDome captcha page** (`geo.captcha-delivery.com`) on both
  `www.blablacar.hr` and `edge.blablacar.hr`.
* The same pages load fine in an Android **WebView** (no captcha shown, DataDome issues a cookie via
  `ddome-tag.blablacar.com/js/`). That cookie lives in the WebView's cookie jar, so it is the only practical client.
* The official `public-api.blablacar.com/api/v3` needs a partner API key and does not expose the driver's real origin.

## Session / auth

The SPA (`www.blablacar.hr`) calls `https://edge.blablacar.hr/...` with these headers (captured from its own fetches):

| header | example |
|---|---|
| `authorization` | `Bearer <anonymous guest token uuid>` |
| `x-visitor-id` | uuid |
| `x-client` | `SPA\|1.0.0` |
| `x-locale` / `accept-language` | `hr_HR` / `hr-HR` |
| `x-currency` | `EUR` |
| `x-correlation-id` | random uuid per request |

Without `authorization` the edge returns `401 SECURITY_UNAUTHORIZED`. The token is anonymous (no login) and is minted
by the page itself, so the app lets the page make its first call and copies the headers from `window.fetch`.

## Loading `/search?...` directly does not work

A cold load of `/search?fn=..&from_place_id=..` redirects to `/search-car-sharing` (home). The URL only works as an
in-SPA navigation. So the app loads the home page once and calls the edge API **from inside the page** with the captured
headers (same origin context, DataDome cookie attached).

## Place ids

`GET /location/suggestions?query=Graz&with_user_history=false` returns `[{address, id, ...}]`. `id` is base64 of
`{"i":"<google place id>","p":1,"v":1,"t":[4]}`. Used verbatim as `from_place_id` / `to_place_id` (URL-encoded).

| city | id |
|---|---|
| Krapina, Hrvatska | `eyJpIjoiQ2hJSndfZkdacEx0WlVjUlV6QXZmR3diYU1zIiwicCI6MSwidiI6MSwidCI6WzRdfQ==` |
| Zagreb, Hrvatska | `eyJpIjoiQ2hJSk9jd0N5WkxXWlVjUmlzTDdLSllrUlRvIiwicCI6MSwidiI6MSwidCI6WzRdfQ==` |
| Graz, Austrija | `eyJpIjoiQ2hJSnUyVXdGNGMxYmtjUm05M2YwdEdLanY0IiwicCI6MSwidiI6MSwidCI6WzRdfQ==` |
| Vienna, Austrija | `eyJpIjoiQ2hJSm44bzJVWjRIYlVjUlJsdWlVWXJsd3YwIiwicCI6MSwidiI6MSwidCI6WzRdfQ==` |

## Search

```
GET https://edge.blablacar.hr/trip/search/v9
    ?from_place_id=..&to_place_id=..&departure_date=YYYY-MM-DD&requested_seats=1
    &supply=CARPOOLING&search_origin=HOME&jrny_stp=ONE_WAY
    [&from_cursor=<next_cursor>]
```

* `supply=ALL` mixes in BUS and PRO_PARTNER results; `supply=CARPOOLING` is private drivers only (what we want).
* Paging: 10 items per page; `results_content.results.pagination.next_cursor` (base64 of `page=1`) is passed back as
  `from_cursor`. The last page has no `pagination`.
* Item: `results_content.results.search_items[].trip`
  * `multimodal_id.{source,id}`; `search_uuid` is at the top level of the response
  * `travel_details.itinerary.departure.{primary_location,time,coordinates}` and `arrival.{...}`, `is_overnight`
  * `price.tokenized_price[]` = INTEGER `13`, FRACTION `,59`, CURRENCY ` €`
  * `travel_details.provider.profiles[0].driver.{name,rating}`
* **Important for phase 2:** the card shows the *passenger's* pick-up stop labelled with the searched city name
  (e.g. "Graz"), not the driver's true origin. The true origin has to come from the trip page.

## Trip page (`/trip?source=CARPOOLING&id=..&requested_seats=1&search_origin=HOME&search_uuid=..`)

* A cold load of this URL **does** work (unlike `/search`), and the detail data is server-rendered into the HTML as a JS
  object literal (not strict JSON: contains `undefined`). No separate edge call is made.
* `"itinerary":{"legs":[{"type":"activeStop","formattedTime":"17:00","address":{"primary":"Graz","secondary":"Plabutscherstraße 107"},"action":{"type":"EMBEDDED_MAP","coordinates":{"latitude":47.092275,"longitude":15.40102}}}, ... {"type":"inactiveStop","address":{"primary":"Zagreb"}}]}`
* No route polyline is present, so phase 3 needs an external router (OSRM) for the geometry.
* Fetching it same-origin with `fetch('/trip?...')` from inside the page returns the same HTML.
