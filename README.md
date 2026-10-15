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
- **Kiosk basics.** The screen stays on, the app runs fullscreen, and it reconnects with backoff when HA drops.

## Setup

1. In HA, create a long-lived access token: **Profile → Security → Long-lived access tokens**.
2. Create `local.properties` in the project root. It's gitignored; never commit it.
   ```properties
   sdk.dir=/path/to/android/sdk
   ha.url=http://homeassistant.local:8123
   ha.token=<your long-lived token>
   ```
3. Build and install with the tablet connected over ADB:
   ```sh
   ./gradlew installDebug
   ```
   Needs JDK 17+ and Android SDK platform 34.

The app opens the dashboard with the URL path `wall-home`. To pick another one, tap the pencil and choose from the list. To edit a dashboard on the tablet, it must be a "wall" copy; the **Make a wall copy to edit here** button makes one. This keeps tablet edits from changing your main dashboards.

## Supported cards

| HA card | Drawn as |
|---|---|
| `heading`, `vertical-stack`, `horizontal-stack`, `grid` | layout |
| `entities`, `entity`, `tile`, `button`, `light`, `sensor`, `thermostat` | entity rows |
| Mushroom: light, entity, template, climate, number, fan, cover, lock | entity rows (templates in names aren't rendered yet) |
| `media-control`, `custom:yet-another-media-player`, Mushroom media | media card |
| `clock` | clock |
| `custom:bubble-card` pop-ups | bottom sheet, opened by a `#hash` action |

Unsupported cards are skipped. In edit mode they show as outlined placeholders so you can see what's missing.

Each entity row shows a control that fits the domain: a switch for lights, switches, fans and `input_boolean`; −/+ for thermostats and numbers; play/pause for media; plus covers, locks (asks before locking/unlocking), scenes, scripts, buttons and sensors (timestamps and weather are shown in plain words).

## Not yet

Weather, calendar, camera/picture, graph and map cards · views beyond the first · Jinja templates in card text · HA login flow and mDNS discovery (setup uses a token for now) · drag-and-drop editing · start on boot / home-screen mode · screensaver.

## Project layout

| File | What it does |
|---|---|
| `HaClient.kt` | WebSocket API: auth, live states, service calls, requests, `lovelace_updated` |
| `Lovelace.kt` | dashboard load/save/copy, visibility, action parsing, layout packing, edit operations |
| `Kinds.kt` | maps an entity to its kind, default tap, state text and steppers |
| `Cards.kt`, `Tiles.kt`, `Media.kt` | card and row rendering |
| `MoreInfo.kt` | more-info sheet |
| `Editor.kt` | pencil sheet, QR code, edit toolbar, entity picker |

The pure logic is covered by JVM unit tests: `./gradlew testDebugUnitTest`.
