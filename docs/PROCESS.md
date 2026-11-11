# Process log

## 2026-11-11: planning and recon

* Request: watch blablacar.hr for rides between Krapina, Zagreb, Graz and Vienna every 5 minutes, push on match;
  later a "sort by real start" view and a "find a higher-priced pickup town" search.
* Decisions made with the user: one direction per search (dropdowns), several searches at once, user-set stop time
  plus automatic end after the travel date, hidden in-app WebView as the data source, manual city for phase 2,
  native Kotlin sideloaded app, local notifications plus ntfy.sh.
* Findings (see `blablacar-data.md`): DataDome blocks non-browser clients, direct `/search` loads redirect home, the
  edge API needs the page-minted anonymous Bearer, trip details are server-rendered into the trip page HTML.
* Phone `RZCTB10XSVL` (Galaxy A53, Android 16) reachable over adb; installed, launched and driven from the CLI.
* Toolchain: Gradle 8.13, AGP 8.13, Kotlin 2.0.21, Compose BOM 2024.10.01, compileSdk 36, JDK 21.
  (The existing `spectra_diet` project had no wrapper jar, so the wrapper was generated from a downloaded Gradle.)

## Phase 1: watcher and notifications

Built: `WebFetcher` (hidden WebView, header capture, in-page fetch with pagination), `Parser`, `Repo`
(SharedPreferences JSON), `WatchService` (foreground, specialUse, 5-minute loop, wakes exactly at the earliest stop
time), `Notifier` (local + ntfy), `CaptchaActivity`, Compose UI (list, add, settings), `BootReceiver`.

Verified on the phone:
* two watches (Graz->Zagreb, Zagreb->Graz, 14 Nov) fetched live results (11 and 3 carpool rides)
* local notifications posted and ntfy pushes received on the topic
* one ride arriving after midnight correctly falls outside a 00:00-23:59 window
* see "Phase 1 verification" below for cadence and auto-expiry

### Phase 1 verification (on the phone, 2026-11-11)

* **Cadence:** checks logged at 11:44:05 and 11:49:08 (5 minutes plus fetch time).
* **Auto-expiry:** a watch seeded with a stop time 2.5 minutes ahead ended itself with status "Stop time reached"; the
  service wakes exactly at the earliest stop time instead of waiting for the next tick. A watch whose travel date has
  passed ends the same way ("Travel date has passed").
* **Spacing rule:** after an early wake-up for an expiry, other watches are not re-checked until about 5 minutes have
  passed since their last check ("Check now" bypasses this).
* **Notifications:** local notifications posted for each new match; ntfy topic received the pushes with the trip URL as
  the click target.
* Test watches were seeded straight into SharedPreferences over adb (`run-as`) to avoid driving date pickers by
  coordinate taps, then removed.
* Not verified: boot-time restart (`BootReceiver`) and the captcha flow (DataDome never challenged the WebView).

## Phase 2: day view with real start

Built: `TripParser` (itinerary legs from the trip page's JS object), `Geo` (Nominatim geocoding with persistent cache,
haversine), `DayView` (route/date/location pickers, progressive loading, sort by distance of the driver's real start).

Findings:
* The trip page lists the whole ride's stops. Active stops (with exact coordinates) are where this passenger boards and
  leaves; inactive ones (city name only) are the rest of the driver's route. The driver's true origin is the first stop,
  active or not. Example from the user's second link: Straubing (inactive) -> Graz (OMV, boards 01:40) -> Krapina ->
  Slavonski Brod (inactive).
* Verified on the phone with Graz -> Zagreb for 12 Nov: rides correctly showed real starts such as Olching, Frankfurt am
  Main and Zwolle, with "You would board in Graz (OMV)", sorted by distance from Graz.

**Incident:** while loading that list (trip pages 300 ms apart, after earlier recon traffic) DataDome switched to a hard
block page ("You have been blocked ... behaviour of the browser has caught our attention") for this IP. It is not
solvable like a captcha. I did not try to evade it (no cookie clearing, spoofing or IP tricks). Fix: `WebFetcher` now
detects the block (403 without a captcha URL, or block text on the page), throws `Blocked`, backs off 30 min doubling to
4 h, and sends nothing meanwhile; the watcher shows the status and notifies once; Day view spaces trip reads 2–4 s apart.
Unknown: whether the block came from the HTML trip fetches, the total volume, or the recon session. Not verified: that
the block lifts and that the new throttling avoids it.
