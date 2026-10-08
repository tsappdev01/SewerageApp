# Publishing DIP Field Service on Google Play

App ID `ae.dipark.fieldservice` (fixed forever after the first upload), version 1.0.0, targets
Android 16 (API 36), runs on Android 10+.

## Files here

| File | For |
|---|---|
| `listing.md` | Store texts: name, short and full description, category, contact |
| `icon-512.png`, `feature-graphic.png` | Store graphics (512×512, 1024×500) |
| `data-safety.md` | Answers for *Data safety* and the other *App content* forms |
| `privacy-policy.html` | Draft privacy policy: DIP fills in the `<...>` values, reviews it, publishes it on a DIP website |
| `review-access.md` | Test account and steps for Google's reviewers |

Screenshots: the `screenshots` branch, folder `android/` (made by the "Android screenshots" workflow).

## Steps

DIP (once):
1. Get the D-U-N-S number for DIP (also needed for Apple).
2. Create a **Google Play Console** account of type *Organization* in DIP's name (USD 25) and finish
   Google's organisation and identity checks.
3. Create the upload key and add the four signing secrets (`docs/deploy-steps.md`, C3).
4. Publish `privacy-policy.html` (completed) on a DIP website.

Each release:
1. Push to the branch; the **Android APK** workflow builds, tests, starts the release on an emulator and
   attaches `DIPFieldService-<version>-build<n>-play.aab` to the Releases page.
2. Play Console → *Create app* (first time only): name "DIP Field Service", App, Free.
3. First time: fill in *App content* from `data-safety.md` and `review-access.md`, and the store listing
   from `listing.md`. Turn on **Play App Signing** (default) when the first bundle is uploaded.
4. *Testing → Internal testing* → create a release → upload the `.aab` → add testers' Google accounts.
   No review wait; use this for the pilot.
5. When ready: *Production* (public), or, for DIP staff only, publish as a **private app** through
   managed Google Play to DIP's organisation (needs Google Workspace or an MDM such as Intune).

## Rules to keep

- `versionCode` goes up with every upload (the workflow uses its run number).
- Target the newest Android API within a year of its release, or Play stops accepting updates.
- If the app starts collecting something new (e.g. background location), update `data-safety.md`,
  the privacy policy and the Play forms before the release that does it.
