# Meter Reader app (Android)

The Meter Reader role of the Sewerage & Irrigation Meter Reading System. The app talks to the
Meter Reading API (`../api`); a demo build runs on built-in sample data instead
(`data/FakeMeterRepository.kt`), for showing the screens without a server. The design is the published mock
("Meter Reader app screens"); the requirements are `../docs/spec.md`; the screen mock is `../docs/meter-reader-mock.html`.

## Open and run

1. Open this folder (`android`) in Android Studio. It syncs with the Gradle wrapper (8.11.1), which
   runs on JDK 17–23: if asked, choose **Use JVM 21** (the one bundled with Android Studio).
2. Run the `app` configuration on a phone with Android 10 or newer. Use a real phone for the camera.
3. Unit tests: `./gradlew test` (consumption rules, search, reconciliation — spec Appendix B vectors).

## Connecting to the API

| Build setting (`-P` on the Gradle command line) | Default | Meaning |
|---|---|---|
| `apiBaseUrl` | `http://10.0.2.2:5080/` | API address. `10.0.2.2` is the computer running the emulator; use the server's address on a phone. |
| `useFakeData` | `false` | `true` builds the demo app on sample data. |
| `readerLogin` | empty (debug: `rashid@dip.example`) | The reader the phone belongs to (`LoginEmail` in `vw_MR_Reader`). |
| `deviceLock` | `true` | Ask for the phone's own lock before the app opens. |
| `settingsPin` | empty | First supervisor PIN (4–8 digits). Without it, the first person to open Settings sets one. |

These are **first values only**. The **gear icon** (start screen and Home) asks for the
**supervisor PIN**, then opens **Settings**: server address (with a **Test** button), the reader
on this phone, the phone lock on/off, and **Change supervisor PIN**. The phone keeps them. Changing
the server or reader is refused while readings still wait on the phone.

Example: `./gradlew installDebug -PapiBaseUrl=http://192.168.1.20:5080/`

1. Start the API in Development as in `../api/README.md` (it accepts the `X-Dev-User` sign-in).
2. After the phone's lock, the app **opens straight to Home** as the reader set in Settings. If
   that fails (not a reader, shared sign-in name, no open period, no server), the start screen
   shows the reason, a **Try again** button and the gear.
3. Readings go to `POST /api/v1/readings`, then each photo with `PUT .../images/{imageId}`.
   Photos are shrunk when taken (long edge 1,600 px, about 500 KB, turned upright), uploaded with
   their SHA-256, and deleted from the phone once the server has them. A photo that cannot be sent
   waits and goes up later; "My summary" shows how many are still on the phone.
4. Readings taken without signal are saved on the phone (purple cloud). When signal is back the
   phone asks **Send now** or **Later** (see "Readings waiting on the phone" below); **Upload now**
   on Home sends at once. They keep their transaction id, so a resend is never stored twice. A
   reading the server refuses shows **Not sent** with its reason.

Debug builds allow plain `http` for the development API; release builds do not.

In the demo build, **Demo: no signal** on the sign-in screen shows the offline path.

## Readings waiting on the phone: encrypted and kept (§9, FR-020)

Readings and photos that cannot be sent yet are kept in one encrypted file (`data/QueueStore.kt`,
in the app's no-backup folder) written after every change, and reloaded when the app starts, so
closing the app or restarting the phone loses nothing. A reading's photos are encrypted on disk
as soon as it is saved (`PhotoVault`) and opened only to upload. Encryption is AES-256-GCM with
one key held by the Android Keystore (`settings/KeystoreKeys.kt`); GCM also detects tampering.

**Sending asks first (FR-020.4).** When signal is back and readings wait, the phone asks **"Signal
is back — Send now / Later"**: a dialog in the app (never during a capture), or, with the app closed,
a notification with the same two buttons (`settings/UploadWorker.kt`, `SyncNotification.kt`;
Android 13+ asks once for permission to notify). **Later** waits 30 minutes before asking again;
**Send now** that cannot finish asks again after 5 minutes. **Upload now** on Home and My readings
still sends at once. A reading taken with signal is sent straight away, as before. Readings keep their transaction id and photos
their image id, so a resend is never stored twice. A queue file that cannot be opened is kept
aside (`queue.mrq.unreadable-…`), not deleted. The spec named Room + SQLCipher; one encrypted file
does the same for a queue of a few hundred readings with less to go wrong.

## Meter list without signal (FR-020.1)

After every sync the meter list (meters, properties, tenants, zones, "my readings") is saved
encrypted (`data/MeterListCache.kt`, same Keystore key). If the app starts with no signal it opens
from that copy, for the same reader and server and only if it is less than 7 days old (the server
refuses older readings anyway); Home shows "No signal: list from Sun 07:15". The copy follows
readings sent since, and is replaced by the fresh list as soon as the server is reached.

## Opening the app: the phone's own lock (FR-001.1)

The app opens with the phone's own lock — its **PIN, pattern or password, or fingerprint or
face** — using Android's standard prompt (`auth/DeviceLock.kt`, androidx.biometric). The app
stores nothing; Android checks it. Then Home opens directly as the reader set in Settings.

- The lock screen covers the app; screens behind it keep their state (a capture in progress is
  not lost).
- After **15 minutes** in the background the lock is asked for again (FR-001.5); shorter breaks
  (taking a call) are not interrupted.
- A phone with **no screen lock** cannot open the app: the lock screen says so, opens the phone's
  security settings, and lets a supervisor (with the PIN) reach Settings to switch the lock off.
- The reader's identity comes from **Settings**, behind the **supervisor PIN**, so readers cannot
  switch to someone else. The PIN is kept only as a salted PBKDF2 hash; five wrong tries block entry
  for a minute. A forgotten PIN is reset by clearing the app's data (MDM or phone settings).

**Registered phones (FR-002).** In Settings, **Register this phone** exchanges a one-time code
from IT for the phone's own id and secret key (`docs/deployment.md` 2.11). The key is encrypted
with an Android Keystore key (`settings/DeviceKeyStore.kt`) and sent with every call
(`X-Device-Id`, `X-Device-Key`, plus `X-Reader`). A blocked phone (`DEVICE_REVOKED`) or an
unregistered one goes back to the start screen with the server's reason; waiting readings stay on
the phone. Without registration the app still works with an API in UAT mode.

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
| Start / sign in (opens Home directly when it can) | `ui/screens/SignInScreen.kt` | FR-001 |
| Lock screen (phone PIN, pattern, finger or face) | `ui/screens/LockScreen.kt` | FR-001.1, FR-001.5 |
| Settings (gear, supervisor PIN): server, reader, lock, PIN | `ui/screens/SettingsScreen.kt` | FR-001.8 |
| Home: progress, Start, search, tiles | `ui/screens/HomeScreen.kt` | FR-003 |
| Zones, properties (filters), meters | `ui/screens/BrowseScreens.kt` | FR-004, FR-005 |
| Find a Property (keypad, letters, voice, filters, highlight) | `ui/screens/SearchScreen.kt` | FR-004.3, FR-021 |
| Capture: condition, reason, camera, number, warning, note, new meter, check, result | `ui/capture/` | FR-006, FR-008, §6 |
| My readings | `ui/screens/ReadingsScreens.kt` | FR-007 |
| My summary (reconciliation) | `ui/screens/ReadingsScreens.kt` | FR-022 |

The capture steps for each meter condition come from `data/StatusRules.kt`, which mirrors spec §6.1.

## Not built yet (marked `TODO(...)` in code)

- The offline grace period (FR-001.6). Entra ID sign-in was built and then replaced by the phone lock
  (2026-10-04); it is in git history (commit 94d5d69) if it is wanted again.
- Wiping a blocked phone's cached data (FR-002.3) and the app-version check (FR-002.6).
- Readings are whole numbers on the phone; `DecimalDigits` from the server is not used yet.
- Image quality check (blur, exposure) (FR-008.4). Photos are shrunk to 1,600 px / about 500 KB,
  encrypted once their reading is saved, and sent with their SHA-256. A photo taken for a capture
  that is abandoned stays unencrypted in the app's private folder until deleted.
- GPS capture (FR-006.8), Play Integrity (FR-002.5), OCR assist (Phase 3).
- Dependency injection (Hilt) in place of `AppGraph`.

## Build status

**GitHub Actions builds the app** (`.github/workflows/android-apk.yml`): on every push that changes
code under `android/` it runs the unit tests (70; the live-API ones skip), builds the debug APK and
attaches it to a pre-release on the repository's **Releases** page. With the signing secrets set it
also builds the release APK signed with your key. First build: 2026-10-04, passed. The app has not
yet been run on a real phone; that is the next check (`docs/deploy-steps.md`, C6 and D).
