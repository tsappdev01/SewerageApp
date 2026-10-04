# Meter Reader app (Android)

The Meter Reader role of the Sewerage & Irrigation Meter Reading System. The app talks to the
Meter Reading API (`../api`); a demo build runs on built-in sample data instead
(`data/FakeMeterRepository.kt`), for showing the screens without a server. The design is the published mock
("Meter Reader app screens"); the requirements are `../docs/spec.md`; the screen mock is `../docs/meter-reader-mock.html`.

## Open and run

1. Open this folder in Android Studio (Ladybug or newer). It syncs with the Gradle wrapper (8.11.1).
2. Run the `app` configuration on a phone with Android 10 or newer. Use a real phone for the camera.
3. Unit tests: `./gradlew test` (consumption rules, search, reconciliation — spec Appendix B vectors).

## Connecting to the API

| Build setting (`-P` on the Gradle command line) | Default | Meaning |
|---|---|---|
| `apiBaseUrl` | `http://10.0.2.2:5080/` | API address. `10.0.2.2` is the computer running the emulator; use the server's address on a phone. |
| `useFakeData` | `false` | `true` builds the demo app on sample data. |
| `devLogin` | `rashid@dip.example` | Sign-in email pre-filled on the sign-in screen (debug builds only). |

Example: `./gradlew installDebug -PapiBaseUrl=http://192.168.1.20:5080/`

1. Start the API in Development as in `../api/README.md` (it accepts the `X-Dev-User` sign-in).
2. On the sign-in screen, enter a reader's `LoginEmail` from `vw_MR_Reader` and press **Sign in**.
   The server's reason is shown if it refuses (not a reader, shared sign-in name, no open period).
3. Readings go to `POST /api/v1/readings`, then each photo with `PUT .../images/{imageId}`.
   Photos are shrunk when taken (long edge 1,600 px, about 500 KB, turned upright), uploaded with
   their SHA-256, and deleted from the phone once the server has them. A photo that cannot be sent
   waits and goes up later; "My summary" shows how many are still on the phone.
4. Readings Without signal they wait on the phone (purple cloud)
   and are sent with the same transaction id when signal returns: on Home, from **Upload now**,
   or by the one-minute retry. A reading the server refuses shows **Not sent** with its reason.

Debug builds allow plain `http` for the development API; release builds do not.

In the demo build, **Demo: no signal** on the sign-in screen shows the offline path.

## Design rules for low-literacy readers

- **One navy button per screen** is always the next step. Everything else is outlined.
- **Pictures before words.** Green drop = irrigation, taupe pipe = sewerage. Green tick = done,
  amber = being checked, purple cloud = waiting to upload, red = read again. Never colour alone.
- **Speaker button on every screen** reads a short instruction aloud (Android text-to-speech, English).
- **Numbers look like the meter.** One box per wheel, filled left to right on a big in-app keypad,
  with the reader's own photo above (pinch to zoom). No system keyboard for readings.
- **Photo first, then the number**, then a side-by-side check before sending.
- **Start goes to the next meter** in route order. Search and browsing are for when a reader is
  sent somewhere specific.
- **English only**, short everyday words, no system terms (see `res/values/strings.xml`).
- **Dubai Investments Park colours**: navy `#12305C` (wordmark) and taupe `#776759` (mark), in
  `ui/theme/Theme.kt`. Light theme only, for use in sun.
- **Logo**: `res/drawable-nodpi/dip_logo.png` on the sign-in screen, made from
  `docs/Dubai-Investments-Park (8).jpg` with the white background made transparent.
- **App icon**: from `docs/ChatGPT Image Oct 4, 2026, 06_00_11 PM.png`, split into an adaptive
  icon's two layers (`ic_launcher_background.png` brown gradient, `ic_launcher_mark.png` white "D")
  so every phone's icon shape works. `play-store-icon-512.png` is the 512 px icon for Google Play.
- **Tenant check** (spec FR-006.12): the check screen lists the property's current tenants and the
  reader taps the one on site; "Yes, send" stays off until they do, even when there is only one.
  A property with no tenant cannot be read. `data/TenantRules.kt` mirrors the server, and the
  repository refuses an unchecked reading before it is sent or saved for later.
- **Sub-tenant**: optional "Add sub-tenant name" on the check screen, sent with the reading.

## Screens

| Screen | File | Spec |
|---|---|---|
| Sign in | `ui/screens/SignInScreen.kt` | FR-001 |
| Home: progress, Start, search, tiles | `ui/screens/HomeScreen.kt` | FR-003 |
| Zones, properties (filters), meters | `ui/screens/BrowseScreens.kt` | FR-004, FR-005 |
| Find a Property (keypad, letters, voice, filters, highlight) | `ui/screens/SearchScreen.kt` | FR-004.3, FR-021 |
| Capture: condition, reason, camera, number, warning, note, new meter, check, result | `ui/capture/` | FR-006, FR-008, §6 |
| My readings | `ui/screens/ReadingsScreens.kt` | FR-007 |
| My summary (reconciliation) | `ui/screens/ReadingsScreens.kt` | FR-022 |

The capture steps for each meter condition come from `data/StatusRules.kt`, which mirrors spec §6.1.

## Not built yet (marked `TODO(...)` in code)

- Entra ID sign-in with MSAL (FR-001): the API client already sends `Authorization: Bearer` when
  `ApiClient.accessToken` is set; until then debug builds use the development sign-in.
- Device registration (FR-002).
- Keep the upload queue in an encrypted Room database and send it with WorkManager (§9, FR-020).
  Today the queues live in memory, so readings and photos waiting to upload are lost if the app
  is closed. Photo files are app-private but not yet encrypted (FR-008.6).
- Readings are whole numbers on the phone; `DecimalDigits` from the server is not used yet.
- Image quality check, resize to 1,600 px / 500 KB, encrypted image files, SHA-256 (FR-008.4–.6, FR-009).
- GPS capture (FR-006.8), Play Integrity (FR-002.5), OCR assist (Phase 3).
- Dependency injection (Hilt) in place of `AppGraph`.

## Build status

The project was written in an environment without the Android SDK, so it has **not been
compiled for Android yet**; expect small compile fixes on first sync. The plain-Kotlin parts
(`data/`, `api/`, `util/Format.kt`) compile and their 35 unit tests pass on the JVM, including
the repository against a scripted server (MockWebServer) and, with `MR_API_URL` set, against
the running API.
