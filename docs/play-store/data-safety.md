# Google Play "Data safety" and app-content answers

Play Console → Policy → App content. Answers match what the app does today (check again if it changes)
and must match `privacy-policy.html`.

## Data safety

**Does the app collect or share any of the required user data types?** Yes.
**Is all collected data encrypted in transit?** Yes (HTTPS to the DMZ gateway only).
**Do you provide a way for users to request that their data is deleted?** Yes — through DIP IT
(phone registrations are withdrawn by a supervisor; give the support email).

Nothing is **shared** with third parties (data goes only to DIP's own server). No ads, no analytics,
no crash-reporting service.

| Data type (Play's names) | Collected | Why (Play's purposes) | Optional? |
|---|---|---|---|
| Location → Precise location | Yes, only while recording a field inspection visit | App functionality | Required for inspections |
| Personal info → Name; Email address; User IDs | Yes: the reader's DIP account and name; names of occupants written in inspection notes | App functionality; Account management | Required |
| Photos and videos → Photos | Yes: photos of meters and units, the occupant's signature on inspections | App functionality | Required |
| App activity → Other user-generated content | Yes: readings, conditions, notes, inspection results | App functionality | Required |
| Device or other IDs | Yes: an app-made device ID and key (not the phone's hardware IDs), phone model and Android version at registration | App functionality; Fraud prevention, security | Required |

Not collected: contacts, messages, audio files, files, calendar, health, financial info, web
browsing, installed apps. Voice search uses the phone's own speech service; the app receives text only.

## Other app-content forms

- **Privacy policy**: URL of the published `privacy-policy.html`.
- **Ads**: No ads.
- **App access**: "All or some functionality is restricted" → instructions from `review-access.md`.
- **Content rating**: questionnaire category "Utility, productivity, communication or other"; answer
  No to all content questions → Everyone / PEGI 3.
- **Target audience**: 18 and over only. Not designed for children.
- **News app**: No. **Government app**: No. **Financial features**: None. **Health**: None.
- **Permissions**: camera and foreground location only; no background location, no SMS/call log, no
  all-files access — no special declarations needed.
- **Foreground service**: none.
