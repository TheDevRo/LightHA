<img src="docs/icon.png" width="96" alt="LightHA icon">

# LightHA

A native Android wall-tablet app for Home Assistant. It draws your **existing HA dashboards** with Kotlin/Compose instead of a WebView, so it stays fast on old tablets (it runs on a 2014 Fire HD 6 with Android 5).

Your HA dashboard is the source of truth. Edit it in HA on your phone, or on the tablet itself, and every screen updates within a second.

## Features

- **Renders HA dashboards natively.** Sections, headings, stacks, grids, card widths (`grid_options.columns`), badges and `visibility` conditions all carry over.
- **Your tap, double-tap and hold actions from HA work the same**, including `confirmation:` prompts and bubble-card pop-ups (`navigate` to `#hash`).
- **More-info sheet** on hold, or wherever HA would open one. It has controls for the entity type: brightness, color temperature and color for lights; target temperature and mode for thermostats; position for covers; transport and volume for media; plus the full attribute list.
- **Media card** with album art (loaded through HA's image proxy, so no internet needed), live progress, controls that match what the player supports, volume, and chips to switch players.
- **Tablet-side editor.** Tap the pencil (top right) to reorder, rename, remove or add cards, then save back to HA. Before saving it re-fetches the dashboard and warns if someone else changed it in the meantime.
- **Edit on your phone.** The pencil sheet shows a QR code that opens the same dashboard in HA's editor.
- **Live reload.** The app listens for HA's `lovelace_updated` event, so a save from anywhere updates the tablet.
- **Multiple views.** Each view gets a tab, and you can swipe between them. Subviews and `navigate` actions work, the app reopens on the last view, and it can return to the first view after a set idle time.
- **Universal remote card**, drawn natively. It honours your `rows` and `custom_actions`, supports hold-to-repeat, has a swipe touchpad and text entry, and sends keys the right way per platform: Fire TV through `androidtv.adb_command`, Apple TV and Android TV through `remote.send_command`.
- **On-tablet settings** (gear icon): HA address and sign-in (account or long-lived token), dashboard, which views show (all are on by default), return-to-first-view timer, keep screen on, fullscreen, brightness, tablet editing on/off, and an optional PIN for settings and editing.
- **View backgrounds:** each view's HA background image, at its opacity.
- **Kiosk mode** (Settings → Kiosk):
  - **Use as home screen:** the Home button and reboots open the dashboard.
  - **Start after reboot:** for when you don't use home-screen mode.
  - **Screensaver:** after a set idle time, a black screen with a dim clock that moves every minute to avoid burn-in, at its own low brightness. Any tap wakes it.
  - **Screen follows an entity:** pick any HA entity (motion, a room's lights, someone home). The screen wakes when it turns on and sleeps when it turns off, including under Android's own screensaver (for example ImmichFrame).
  - **Wake on camera motion:** the tablet's camera compares tiny grayscale snapshots once a second (nothing is saved or sent). Motion wakes the screen and reports `binary_sensor.kiosk_<device>_motion` to HA (admin accounts only).
- **Kiosk basics.** The screen stays on, the app runs fullscreen, and it reconnects with backoff when HA drops.

## Install

1. Download `LightHA-<version>.apk` from [Releases](https://github.com/TheDevRo/LightHA/releases) and install it on the tablet. Android 5.0 or newer.
2. Open LightHA. On first launch it shows Settings: enter your HA address (for example `http://homeassistant.local:8123`), then sign in with your HA username and password (and 2-factor code, if you use one). The tablet keeps only a refresh token; remove it any time under **Profile → Security** in HA.
3. Prefer a long-lived token? Pick **Long-lived token** instead and paste one from **Profile → Security → Long-lived access tokens**.

Non-admin accounts see only the dashboards and views HA shows them, and can't edit from the tablet.

Release APKs contain no server address or token. Everything is entered on the tablet.

## Build from source

Create `local.properties` in the project root. It's gitignored; never commit it.

```properties
sdk.dir=/path/to/android/sdk
# Optional: prefill debug builds so a fresh install connects right away
ha.url=http://homeassistant.local:8123
ha.token=<your long-lived token>
# Optional: release signing (without these, release builds use the debug key)
release.store=/path/to/lightha-release.jks
release.password=<keystore password>
```

Then run `./gradlew installDebug`, or `./gradlew assembleRelease` for a shrunk, signed APK. Needs JDK 17+ and Android SDK platform 34.

The app opens the dashboard with the URL path `wall-home`. To pick another one, tap the pencil and choose from the list. To edit a dashboard on the tablet, it must be a "wall" copy; the **Make a wall copy to edit here** button makes one. This keeps tablet edits from changing your main dashboards.

## Supported cards

| HA card | Drawn as |
|---|---|
| `heading`, `vertical-stack`, `horizontal-stack`, `grid` | layout |
| `entities`, `entity`, `tile`, `button`, `light`, `sensor`, `thermostat` | entity rows |
| Mushroom: light, entity, template, climate, number, fan, cover, lock | entity rows; template `primary` / `secondary` / `color` are rendered live by HA |
| `media-control`, `custom:yet-another-media-player`, Mushroom media | media card |
| `custom:weather-forecast-card`, `weather-forecast` | current conditions + hourly/daily forecast |
| `custom:calendar-card-pro`, `calendar`, `custom:atomic-calendar-revive` | event list grouped by day with per-day weather and font sizes; month grid for `dayGridMonth` and atomic Calendar mode |
| `custom:mini-graph-card`, `sensor` with `graph:` | sparkline from HA long-term statistics |
| `picture-entity`, `picture-glance`, `custom:advanced-camera-card` | refreshing snapshots, PTZ arrows |
| `todo-list` | live list: check off and add items |
| `markdown` | rendered text, templates included; common `card_mod` styles (font size/weight, alignment, colors, corner radius) |
| `clock` | clock |
| `custom:universal-remote-card` | remote |
| `custom:bubble-card` pop-ups | bottom sheet, opened by a `#hash` action |

Unsupported cards are skipped. In edit mode they show as outlined placeholders so you can see what's missing.

Each entity row shows a control that fits the domain: a switch for lights, switches, fans and `input_boolean`; −/+ for thermostats and numbers; play/pause for media; plus covers, locks (asks before locking/unlocking), scenes, scripts, buttons and sensors (timestamps and weather are shown in plain words).

## Not yet

Map card (needs internet map tiles) · live camera video · `card_mod` on cards other than markdown · calendar weather per event · mDNS discovery · drag-and-drop editing · speaker grouping and media browsing.

## Project layout

| File | What it does |
|---|---|
| `HaClient.kt` | WebSocket API: auth, live states, service calls, requests, `lovelace_updated` |
| `Lovelace.kt` | dashboard load/save/copy, visibility, action parsing, layout packing, edit operations |
| `Kinds.kt` | maps an entity to its kind, default tap, state text and steppers |
| `Cards.kt`, `Tiles.kt`, `Media.kt` | card and row rendering |
| `MoreInfo.kt` | more-info sheet |
| `Editor.kt` | pencil sheet, QR code, edit toolbar, entity picker |
| `Kiosk.kt` | home-screen mode, start on boot, screensaver |
| `Remote.kt` | universal-remote-card and per-platform key dispatch |
| `Settings.kt`, `SettingsScreen.kt` | on-tablet settings |

The pure logic is covered by JVM unit tests: `./gradlew testDebugUnitTest`.
