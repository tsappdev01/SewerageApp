# Meter Reader app (Android)

The Meter Reader role of the Sewerage & Irrigation Meter Reading System, built UI-first.
Every screen runs on in-memory sample data (`data/FakeMeterRepository.kt`), so it can be
tried with real readers before the API exists. The design is the published mock
("Meter Reader app screens"); the requirements are `../docs/spec.md`; the screen mock is `../docs/meter-reader-mock.html`.

## Open and run

1. Open this folder in Android Studio (Ladybug or newer). It syncs with the Gradle wrapper (8.11.1).
2. Run the `app` configuration on a phone with Android 10 or newer. Use a real phone for the camera.
3. Unit tests: `./gradlew test` (consumption rules, search, reconciliation — spec Appendix B vectors).

On the sign-in screen, **Demo: no signal** makes the app save readings on the phone, so the
offline path (purple "waiting to upload", summary mismatch, Upload now) can be shown.

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

- Entra ID sign-in with MSAL (FR-001), device registration (FR-002).
- Real API client and encrypted offline database: Room + SQLCipher outbox, WorkManager upload (§9, FR-020).
- Image quality check, resize to 1,600 px / 500 KB, encrypted image files, SHA-256 (FR-008.4–.6, FR-009).
- GPS capture (FR-006.8), Play Integrity (FR-002.5), OCR assist (Phase 3).
- Dubai Investments Park logo: add `res/drawable/dip_logo.png` and replace the placeholder on the
  sign-in screen and the launcher icon foreground.
- Dependency injection (Hilt) in place of `AppGraph`.

## Build status

The project was written in an environment without the Android SDK, so it has **not been
compiled for Android yet**. The plain-Kotlin parts (`data/`, `util/Format.kt`) were compiled
and their 15 unit tests pass on the JVM. Expect to fix small compile issues on first sync.
