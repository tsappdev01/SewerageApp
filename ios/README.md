# DIP Field Service for iPhone and iPad

One app for both iPhone and iPad. Everything on the screen, and every rule, is the same Kotlin code as
the Android app (`android/app/src/commonMain`); `android/app/src/iosMain` is what iOS does its own way;
this folder is only the Xcode shell that hosts it.

| Area | Android | iOS |
|---|---|---|
| Screens, texts, rules, offline queue, server calls | shared (`commonMain`) | shared |
| Encryption of what is kept on the phone | Android Keystore, AES-GCM | iOS Keychain key (Secure Enclave where present), ECIES with AES-GCM; files also under iOS file protection, not in backups |
| Phone lock (FR-001.1) | PIN, pattern, finger or face | Face ID, Touch ID or passcode |
| Camera (FR-008) | in-app camera with the white box | the iOS camera; the white box and hint are shown first |
| "Signal is back" (FR-020.4) | dialog in the app, or a notification | dialog in the app; a reminder 30 minutes after leaving the app with work waiting (iOS does not let apps wait for signal in the background) |
| Read aloud | Android text-to-speech | iOS voice (en-GB) |
| Voice search | yes | not yet: type, or use the keyboard's dictation button |
| Block screenshots | yes | not possible on iOS |

## Build on a Mac

Needs Xcode 16, JDK 17, and [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`).

```bash
cd ios
xcodegen generate          # makes DIPFieldService.xcodeproj from project.yml
open DIPFieldService.xcodeproj
```

Pick an iPhone or iPad simulator and Run. The first build compiles the shared Kotlin code (about
10 minutes); later builds are quicker. To try it with the demo data and no lock, set these build
settings on the target (or pass them to `xcodebuild`): `MR_USE_FAKE_DATA=YES MR_DEVICE_LOCK=NO
MR_READER_LOGIN=rashid@dip.example`. The workflow **iOS app** does exactly that on GitHub and keeps
iPhone and iPad screenshots on the `screenshots-ios` branch.

First values for phones (changed later in Settings, behind the supervisor PIN) are build settings in
`project.yml`: `MR_API_BASE_URL` (default `https://zApps.dipark.com/`), `MR_READER_LOGIN`,
`MR_DEVICE_LOCK`, `MR_SETTINGS_PIN`. `MARKETING_VERSION` and `CURRENT_PROJECT_VERSION` match Android's
`versionName` and `versionCode`.

## Putting it on real iPhones and iPads

iOS apps cannot be installed from a file like an APK. DIP needs:

1. **Apple Developer Program, as an organisation** ($99 a year). Enrolling needs DIP's D-U-N-S number.
2. A **bundle id**: `com.meterreading.reader` (as Android), or change `PRODUCT_BUNDLE_IDENTIFIER` in
   `project.yml` before the first upload; it cannot change afterwards.
3. Then one of:
   - **TestFlight** (pilot): upload a build from Xcode (*Product → Archive → Distribute App → App Store
     Connect*), add testers by e-mail; they install the TestFlight app and then DIP Field Service.
     Each build works for 90 days.
   - **Apple Business Manager, custom app** (field use): the app is published privately to DIP's
     Apple Business Manager and given to devices with or without an MDM. Not visible on the public
     App Store.
   - **Ad Hoc**: up to 100 registered devices per year, installed from a link or by the MDM.

Every route goes through Apple's review. Apple will ask for a test login: give them a registration
code (`db/ops/new_device_code.sql`) and a reader e-mail on a test server they can reach, or build
with `MR_USE_FAKE_DATA=YES` for the review build.

On the phone, setup is the same as on Android: gear → supervisor PIN → server → registration code →
reader. iOS asks once each for the camera, location (inspections) and notifications.
