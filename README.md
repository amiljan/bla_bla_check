# BlaBla Check

A personal Android app that watches **blablacar.hr** for carpool rides between **Krapina, Zagreb, Graz and Vienna**
and pushes a notification when a ride shows up that arrives inside the time window you set.

Native Kotlin + Jetpack Compose, sideloaded over `adb`. No server, no account, no Play Store.

## Status

| Phase | What | State |
|---|---|---|
| 1 | Watch routes every 5 min, notify on match (local + ntfy.sh) | done |
| 2 | Day view of rides sorted by the driver's *real* start vs. a city you pick | done (see the block warning below) |
| 3 | Find a pickup town along the route where the fixed price is about 10 / 20 EUR higher | planned |

## How it works

blablacar.hr sits behind DataDome bot protection: plain HTTP clients (curl, OkHttp) get a 403 captcha page, and the
official API needs a partner key. So the app drives a real **Android WebView**:

1. A foreground service (`WatchService`) wakes every 5 minutes.
2. One hidden WebView loads `https://www.blablacar.hr/`. The site mints an anonymous guest token; a document-start script
   copies the headers of the site's own API calls.
3. The app calls `edge.blablacar.hr/trip/search/v9` **from inside the page** with those headers (all pages of results,
   `supply=CARPOOLING` so buses and partner coaches are excluded).
4. Rides whose arrival time falls in your window and that were not seen before trigger a notification, with a tap-through
   to the trip page.

Details of the endpoints and JSON are in [`docs/blablacar-data.md`](docs/blablacar-data.md); the build log is in
[`docs/PROCESS.md`](docs/PROCESS.md).

## Using it

* **+** adds a search: From / To (dropdowns of the 4 cities, one direction per search), travel date, arrival window,
  and a stop time. You can run as many searches as you like at once.
* A search ends automatically at its stop time and, regardless, when its travel date has passed. Ended searches stay
  in the list (greyed status) until you delete them.
* **Settings**
  * *ntfy topic*: a random topic is generated on first run. Install the [ntfy](https://ntfy.sh) app on any device and
    subscribe to it to get the same alerts there. Treat the topic as a secret. Clear it to disable ntfy.
  * *Disable battery optimisation*: needed on Samsung and similar, otherwise Android may stop the checker.
  * *Open BlaBlaCar session / solve captcha*: if DataDome ever shows a captcha, you get a "captcha needed" notification;
    open this and solve it once. The hidden checker shares that browser session.

Notification permission (Android 13+) is requested on first launch.

## Build and install

Requirements: JDK 21, Android SDK (platform 36), a phone with USB debugging.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` (git-ignored) must contain `sdk.dir=/path/to/Android/Sdk`.

## Day view (phase 2)

Top-bar **Day view**: pick route, date and "my location" (one of the 4 cities, or "Other…" and type a place name, which
is geocoded with OpenStreetMap Nominatim). The app lists every carpool ride that day, reads each trip page to find the
driver's real first stop (for example a ride that the search card labels "Graz" but that really starts in Straubing),
and sorts by the straight-line distance from that start to your location. Starts that are only a city name (no exact
address) are geocoded to the city centre and shown with "≈". Tapping a ride opens its blablacar page.

## Limitations and caveats

* **BlaBlaCar can block you.** Fetching ~10 trip pages 300 ms apart during testing made DataDome answer "You have been
  blocked" for the phone's IP and session. That is a hard block, not a captcha you can solve. The app now spaces trip-page
  reads 2–4 s apart, recognises the block, stops sending requests, backs off 30 min (doubling up to 4 h on repeats) and
  notifies you once. Do not hammer Day view; results are cached per app run.

* Unofficial: it relies on blablacar's private web API, which can change without notice. When it does, fixing the
  parser in `Parser.kt` / the URL in `WebFetcher.kt` is the place to start.
* Polling a site every 5 minutes is gentle, but it is still automated access to a site whose terms you should be
  comfortable with. Personal use only.
* The search card shows the passenger's pick-up stop labelled with the searched city. The driver's true origin is only
  on the trip page (used in phase 2).
* Search times come from the cards; for rides that cross midnight the arrival counts as the next day (the window is
  compared against the travel date's clock, so such rides only match windows that extend to 23:59 at the latest if they
  arrive before midnight).
* Android may still defer the service on aggressive battery managers even when exempted. Check the status line on each
  search for the last check time.
