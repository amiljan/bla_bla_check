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
