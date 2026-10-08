# Access for Google's reviewers

The app opens only on a registered phone with an active reader, so Google's reviewers must be given
a way in, or the review is rejected ("App access" in Play Console → App content).

Before submitting, DIP prepares:

1. A **review reader** in `vw_MR_Reader` (active), e.g. `playreview@dipark.com`, with one zone of
   real or test meters and, if inspections are to be reviewed, one inspection plan row.
2. **Registration codes**, valid for at least 30 days (`new_device_code`, label "Play review 1..5").
   A code works once and Google reviews on several devices, so give five codes.
3. The **server reachable from the internet** through the DMZ gateway (https://zApps.dipark.com/).
4. A **supervisor PIN** for the review: the app is built with it (repository variable
   `MR_SETTINGS_PIN`), or the reviewer creates one on first opening Settings.

Text for the "App access" instructions (fill in the values):

> This app is for Dubai Investments Park staff. To review it:
> 1. Open the app and tap the gear (Settings). Supervisor PIN: <PIN> (if asked to choose one, choose any
>    4 numbers).
> 2. Server address: https://zApps.dipark.com/ — Save.
> 3. Registration code: use one of <CODE 1> … <CODE 5> (each works once) — Register this phone.
> 4. Reader on this phone: <READER EMAIL> — Save, then go back.
> 5. If the app asks for the phone's lock, use the device's PIN.
> 6. Tap Start to read a meter (any photo; any number). Field Inspection shows today's visits.

After the review, withdraw the review phones' registrations and set the review reader inactive.
