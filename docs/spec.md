# Sewerage & Irrigation Meter Reading System — Functional & Technical Specification

| Item | Value |
|---|---|
| Document | Functional & Technical Requirements Specification |
| Version | 1.1 (Draft — completed for development) |
| Date | 2026-10-04 |
| Status | For review |
| Owner | **To be confirmed** |
| Approvers | Business owner, IT architecture, Information security, Billing system owner |

> **How to read this version.** v1.0 covered sections 1 and 3–6. v1.1 fixes the contradictions
> found in review and adds the missing sections (2 and 7–20, plus appendices). Where the
> business had not decided something, this document states a **proposed default** marked
> **`[ASSUMPTION]`** and lists it in §20 *Open decisions*. Development may proceed on an
> assumption; changing it later must only require configuration or a localised code change.
> Appendix C lists every change from v1.0.

---

## 1. Introduction

This specification defines what the Sewerage & Irrigation Meter Reading System must do and how it
will be built. Field readers capture readings and photo evidence on Android; a secure Azure-hosted
.NET API validates them and passes accepted readings to the utility billing system.

### 1.1 Purpose

The system gives field personnel and supervisors a secure platform to:

- View assigned zones, properties and meters.
- Capture sewerage and irrigation readings with a camera photograph as evidence, online or offline.
- Optionally extract the reading through OCR, always confirmed by the reader.
- Record meter condition, including damaged, submersed, inaccessible, new, replaced and removed meters.
- Validate readings on the server against previous readings and business rules.
- Submit readings as soon as connectivity allows and pass accepted readings to billing.
- Keep a complete audit trail and let supervisors manage exceptions.

### 1.2 Scope

| In scope | Out of scope |
|---|---|
| Android meter reading app (Kotlin) | Changes inside the billing system itself |
| Secure REST API (ASP.NET Core) | Customer-facing portal or self-service reading |
| Meter reading SQL Server database | Billing calculation and tariff logic (owned by billing) |
| Image storage (Azure Blob) | Physical meter installation and maintenance work orders (the system only *raises referrals*) |
| Billing integration layer | iOS client |
| Supervisor / Admin web portal — minimal exception queue from Phase 4, full portal Phase 6 | Utilities other than sewerage and irrigation (design allows them later) |
| Background worker (billing outbox, period jobs, revisit tasks) | |

### 1.3 Audience

Business owners, project managers, solution architects, Android and .NET developers, DBAs,
security reviewers, testers, the billing integration team, and AI coding agents (Claude Code)
implementing the system.

### 1.4 Definitions

| Term | Meaning |
|---|---|
| Zone | Geographic reading area containing properties (e.g. 597) |
| Property | A site or building containing one or more meters. Identified by a unique **property code** (e.g. `1100`, `1499-W1`); also has a display name |
| Meter | A physical sewerage or irrigation meter belonging to one property |
| Meter number | Unique identifier of a meter, **unique across the whole system** `[ASSUMPTION]` |
| Register | The meter's counter. `RegisterDigits` = whole-number digits; `DecimalDigits` = fractional digits (default 0) |
| Register maximum | `10^RegisterDigits − 1` (e.g. 5 digits → 99,999) |
| Reading period | The billing period a reading belongs to (monthly `[ASSUMPTION]`), e.g. `2026-10` |
| Assignment | A meter allocated to one reader for one reading period |
| Previous reading | The most recent **approved actual** reading of the meter, or its opening reading if none |
| Consumption | New reading − previous reading (with rollover handling, BR-006) |
| Billing consumption | Consumption the server sends to billing; may be an **average** for exceptional meters |
| Consumption basis | How billing consumption was derived: `ACTUAL`, `ROLLOVER`, `AVERAGE`, `SPLIT` (replacement) |
| Exception | A reading that breaks a validation rule and is **held from billing** until a supervisor decides |
| Flag | A marker on an accepted reading for sampling or reporting; **does not** hold billing |
| Rejection | A submission the server refuses; nothing is stored as a reading and the reader must re-capture |
| Transaction ID | Client-generated UUID that makes each submission idempotent |
| OCR | Optical character recognition of the meter display |
| LOV | List of values controlled by administrators |
| Outbox | Server table holding billing messages until billing acknowledges them |

### 1.5 Requirement language

"Shall" marks a mandatory requirement. "Should" marks a recommended one. "May" marks an option.
Requirement IDs: **FR** (functional), **BR** (business rule), **SEC** (security), **NFR**
(non-functional), **INT** (integration). Every requirement ID must be traceable to code and tests
(see §19).

---

## 2. System overview

### 2.1 Context

```
 ┌──────────────┐  HTTPS/OAuth2   ┌────────────────────────────── Azure ─────────────────────────────┐
 │ Android app  │ ──────────────▶ │  Meter Reading API (ASP.NET Core)                                │
 │ (Kotlin)     │                 │    ├── Azure SQL / SQL Server  (readings, master data, audit)    │
 │ offline DB   │                 │    ├── Azure Blob Storage      (images, private)                 │
 └──────────────┘                 │    └── Key Vault, App Insights                                   │
 ┌──────────────┐  HTTPS/OIDC     │  Supervisor/Admin portal (Blazor)  ── uses same DB via API layer │
 │ Web browser  │ ──────────────▶ │  Worker (background service)                                     │
 └──────────────┘                 │    ├── billing outbox dispatcher ──────────▶ Billing system      │
                                  │    └── period, average, revisit jobs  ◀──── billing acks (INT)   │
                                  └──────────────────────────────────────────────────────────────────┘
                 Microsoft Entra ID: sign-in, app roles, service principals, managed identities
```

### 2.2 Components

| Component | Responsibility |
|---|---|
| Android app | Sign-in, assignment cache, capture (reading, status, photos, GPS, OCR), offline queue, sync |
| Meter Reading API | AuthN/AuthZ, validation, consumption calculation, persistence, image ingest, sync endpoints, supervisor/admin endpoints |
| Portal | Supervisor exception queue, progress monitoring, reassignment; admin master data, LOV, settings, devices, periods, audit |
| Worker | Billing outbox dispatch and retries, average calculation, period open/close jobs, revisit task generation, escalation rules |
| SQL database | System of record for readings, master data, configuration and audit |
| Blob storage | Immutable reading images |
| Entra ID | Identity, MFA/conditional access, app roles, service principals |

### 2.3 Proposed technology baseline `[ASSUMPTION — confirm with IT architecture]`

| Layer | Choice |
|---|---|
| Android | Kotlin, Jetpack Compose, minSdk 29 (Android 10), targetSdk = current Play requirement, Hilt, Room + SQLCipher, WorkManager, Retrofit/OkHttp + kotlinx.serialization, CameraX, ML Kit Text Recognition v2, MSAL for Android, Play Integrity API |
| Backend | .NET 10 (LTS), ASP.NET Core, EF Core 10 (migrations own the schema), Microsoft.Identity.Web, OpenTelemetry → Application Insights |
| Portal | ASP.NET Core Blazor Web App (server interactivity), Microsoft.Identity.Web |
| Data | Azure SQL Database (or SQL Server 2022), Azure Blob Storage (private, versioning on) |
| Hosting | Azure App Service or Container Apps, managed identities, Key Vault |
| IaC / CI | Bicep, GitHub Actions |
| Distribution | Managed Google Play via Microsoft Intune |

---

## 3. User roles and permissions

Four roles are defined, each mapped to a Microsoft Entra ID app role and enforced by the API,
never by the app alone. A reader sees only their own assignments; only administrators change
master data.

### 3.1 Role descriptions

| Role | Entra app role | Who | Access channel | Purpose |
|---|---|---|---|---|
| Meter Reader | `MeterReader` | Field staff | Android app | Capture and submit readings with evidence |
| Supervisor | `Supervisor` | Team leads | Web portal (and app read-only views if required) | Monitor progress, review exceptions, reassign work |
| Administrator | `Administrator` | IT / business admin | Web portal | Manage master data, configuration, devices and corrections |
| System Integration | `Integration` | Service principal | Machine-to-machine only | Billing acknowledgements and (optionally) master-data feed; no interactive login |

### 3.2 Permission matrix

"Team" = readers whose `TeamId` the supervisor leads. "Own" = the caller's own records.

| Capability | Reader | Supervisor | Admin | Integration |
|---|---|---|---|---|
| Log in interactively | Yes | Yes | Yes | No |
| View own assignments | Yes | Only if also Reader | Only if also Reader | No |
| View other readers' assignments and progress | No | Team | All | No |
| Capture and submit readings, photos, meter status | Yes | Only if also Reader | No | No |
| Retry own failed transactions | Yes | Only if also Reader | No | No |
| View reading images | Own | Team | All | No |
| Change a submitted reading's value | No | No | No — see "Correct" | No |
| Approve / reject exceptions; choose billing basis (actual vs average) | No | Team | All | No |
| Approve meter replacement / removal | No | Team | All | No |
| Correct an approved reading (audited correction, §8.5) | No | No | Yes | No |
| Reassign properties or meters (current period) | No | Team | All | No |
| Manage users' team/zone mapping, zones, properties, meters | No | No | Yes | No |
| Configure LOVs, thresholds, sync settings | No | No | Yes | No |
| Open / close reading periods | No | No | Yes | No |
| Register or revoke devices | No (self-register only) | No | Yes | No |
| View audit logs | No | Team | All | No |
| Re-send a reading to billing | No | No | Yes | No |
| Receive billing payloads / post acknowledgements | No | No | No | Yes |

### 3.3 Role rules

- **FR-ROLE-01** — Roles shall be assigned in Entra ID and delivered as `roles` claims in the access token.
- **FR-ROLE-02** — The API shall check both role and data scope (assignment, team) on every request.
- **FR-ROLE-03** — A user may hold several roles. For each action, the data scope is that of the
  **role that authorises the action** (e.g. a Supervisor who is also a Reader captures only their own
  assignments, and reviews exceptions for their whole team).
- **FR-ROLE-04** — The integration account shall use the client-credentials flow with a certificate or
  managed identity, never a shared secret or password.
- **FR-ROLE-05** — Team membership and supervisor-of-team are application data (`AppUser.TeamId`,
  `Team.SupervisorUserId`), maintained by administrators. Users are created in the app on first
  sign-in from their Entra object ID; an admin must assign a team before they receive work.

---

## 4. Functional requirements — mobile application

The reader moves through one straight path: log in, see the day's workload, pick a zone, pick a
property, pick a meter, capture and submit. Every screen works on assignment data cached on the
device so a weak signal does not stop navigation or capture (§9).

### 4.1 Authentication (FR-001)

| ID | Requirement |
|---|---|
| FR-001.1 | The app shall authenticate through Microsoft Entra ID using OAuth 2.0 / OpenID Connect with Authorization Code + PKCE (MSAL for Android). |
| FR-001.2 | The app shall never store or transmit user passwords itself. |
| FR-001.3 | Access tokens shall be held in memory; refresh tokens are managed by MSAL in Android Keystore-backed encrypted storage. |
| FR-001.4 | Multi-factor authentication and conditional access shall follow corporate Entra ID policy. |
| FR-001.5 | After a configurable idle time (`IdleTimeoutMinutes`, default 15) the app shall lock and require biometric or device-credential unlock. Unlock is local and works offline. |
| FR-001.6 | The app shall allow offline work for up to `OfflineGraceHours` (default 24) since the last successful online token acquisition. After that it shall require an online sign-in before new captures; queued readings remain queued. |
| FR-001.7 | Logout shall clear tokens and cached assignment data. Unsynchronised readings stay encrypted until sent (see FR-020.8 for user switching). |
| FR-001.8 | A Settings screen (gear icon on the start and Home screens) shall let IT set the API server address, test it, and switch company sign-in (Entra ID) on or off with its values (tenant, app client ID, redirect URI, API scope). While company sign-in is off the app opens straight away with the test sign-in name from Settings (UAT only). Settings cannot be saved while readings wait on the phone. When the company sign-in runs out, the app returns to the start screen and keeps waiting readings. Added 2026-10-04. |

### 4.2 Device registration (FR-002)

| ID | Requirement |
|---|---|
| FR-002.1 | Each installation shall register once, generating a unique Device ID (UUID) stored in encrypted storage, via `POST /api/v1/devices/register`. |
| FR-002.2 | The system shall hold Device ID, last user, device model, Android version, app version, registration date, last sync and status (`Active`, `Revoked`). |
| FR-002.3 | An administrator shall be able to revoke a device; the API shall reject any request from a revoked device with `DEVICE_REVOKED` (HTTP 403). On receiving it the app shall wipe cached data **except** the unsent queue, which stays encrypted and unreadable to the user; an admin may reinstate the device to allow it to drain. |
| FR-002.4 | The app should be deployed and managed through Microsoft Intune (MDM/MAM) where the organisation supports it. |
| FR-002.5 | The app shall refuse to run on rooted devices or failed Play Integrity checks when `RequirePlayIntegrity` = true. The integrity token shall be **verified server-side** at registration and at least daily (SEC-010). |
| FR-002.6 | Every API request from the app shall carry headers `X-Device-Id` and `X-App-Version`. The API shall reject app versions below `MinAppVersion` with `APP_VERSION_UNSUPPORTED` (HTTP 426). |

### 4.3 Dashboard (FR-003)

After login the dashboard shows the current period's assignment counts and three actions.

| Element | Content |
|---|---|
| Counters | Assigned, Completed, Pending, Exceptions (e.g. 125 / 108 / 17 / 3) |
| Start Reading | Opens zone selection |
| My Readings | Lists the reader's submissions with status |
| Sync Status | Shows queued, failed and last successful sync time |

- FR-003.1 — Counters shall refresh on open and after each submission; offline values shall be labelled with the last sync time.
- FR-003.2 — The dashboard shall show a warning banner when any queued item is older than `QueueWarningHours` (default 24).

### 4.4 Zone and property selection (FR-004)

| ID | Requirement |
|---|---|
| FR-004.1 | Work is not assigned to readers (decided 2026-10-04): every active reader may read every active meter in the open period. The app lists all zones; the reader picks the zones they are working in, and lists are narrowed to those. A meter read by anyone shows as done for everyone. |
| FR-004.2 | Selecting a zone shall list its properties (that contain assigned meters) as cards showing property code, name, number of assigned meters and number completed. |
| FR-004.3 | Search shall be provided by property code, property name and meter number, working offline (detailed in FR-021). |
| FR-004.4 | Properties should be sortable by route sequence, code or completion status. Default: route sequence. |
| FR-004.5 | A property should show a map link when coordinates exist (future enhancement). |

### 4.5 Business hierarchy

The app and database follow **Zone → Property → Meter (Irrigation or Sewerage)**. Property code is
an attribute of the property, not a separate level. One property may hold several meters of each
type, for example:

| Zone | Property code | Property name | Meter number | Type |
|---|---|---|---|---|
| 597 | 1100 | Villa 1100 | 1001-I | Irrigation |
| 597 | 1100 | Villa 1100 | 1001-S | Sewerage |
| 598 | 1499-W1 | Building 1499-W1 | 2001-I, 2001-2 | Irrigation |
| 598 | 1499-W1 | Building 1499-W1 | 2002-2 … 2002-6 | Sewerage |

Meter type is a field on the meter; the meter number format is free text (max 30 chars) and does
**not** encode the type `[ASSUMPTION — confirm numbering convention]`.

### 4.6 Meter list (FR-005)

FR-005.1 — Each meter row shall show meter number, meter type, previous reading, previous reading
date, current meter status and **assignment status** (§7.1): `Pending`, `Queued`, `Submitted`,
`Exception`, `Revisit`, `Completed`, `Rejected`.

FR-005.2 — `Completed` and `Submitted` meters shall be visually distinct and read-only. `Rejected`
and `Revisit` meters shall be capturable again.

### 4.7 Meter reading screen (FR-006)

| ID | Requirement |
|---|---|
| FR-006.1 | The screen shall show property (e.g. 597-1100), meter number, type and previous reading with its date. |
| FR-006.2 | Meter status shall be selected from the `MeterStatus` LOV; `WORKING` is the default. |
| FR-006.3 | The new reading field shall be numeric only, limited to `RegisterDigits` whole digits and `DecimalDigits` fractional digits for the meter. |
| FR-006.4 | The app shall display consumption (new − previous, with rollover handling) as the user types, for information only. |
| FR-006.5 | Fields shown and required shall change by meter status per §6.1, driven by the `StatusRules` configuration downloaded from the server (not hard-coded). |
| FR-006.6 | The app shall warn locally when the reading is below previous or outside the expected range (`ExpectedLow`/`ExpectedHigh` sent with each assignment), and still require server validation. |
| FR-006.7 | Submit shall be disabled until all mandatory fields and the minimum photos for that status are complete. |
| FR-006.8 | The app shall capture GPS coordinates and accuracy at submission when location permission is granted; absence of GPS shall not block submission. |
| FR-006.9 | After submit, the user shall see the outcome: `Accepted`, `Exception` (held for supervisor), `Rejected` (with reason, re-capture needed) or `Queued` (offline). |
| FR-006.10 | The reader shall confirm a summary (meter, status, reading, consumption) before final submission. |
| FR-006.11 | Each reading shall record the property's tenant code at the time of reading (the tenant checked under FR-006.12). The reader may type a sub-tenant name on the check screen when the premises has one (optional, up to 100 characters). |
| FR-006.12 | Before a reading is saved or sent, the reader shall check the property's tenant on the check screen: the app lists the property's current tenants (vw_MR_Tenant) and the reader taps the one on site. Nothing is pre-selected, even when there is only one; "Yes, send" stays off until a tenant is tapped. A property with no current tenant cannot be read ("Tell your supervisor"). The server checks again when the reading arrives: the tenant must still be a current tenant of the property, else the reading is refused (`TENANT_NOT_CONFIRMED`, `TENANT_CHANGED`, `NO_TENANT`) and the meter comes back to be read again. Added 2026-10-04. |

### 4.8 My readings and retry (FR-007)

FR-007.1 — The reader shall see their own submissions for the current period, filter by status,
view the server response, and retry any transaction in `Failed` state. Retry shall reuse the
original Transaction ID and payload.

FR-007.2 — A `Rejected` submission cannot be retried as-is; the reader re-captures, which creates a
**new** Transaction ID.

---

## 5. Functional requirements — camera, images and OCR

Every reading carries a live camera photograph as evidence; OCR may suggest the number but the
reader confirms it and the server decides. OCR is introduced in Phase 3, after manual capture is
stable (§17).

### 5.1 Camera (FR-008)

| ID | Requirement |
|---|---|
| FR-008.1 | The app shall use Android CameraX with live preview, tap-to-focus/autofocus, flash on/off/auto, capture and retake. A framing guide shall show where to place the meter display. |
| FR-008.2 | Evidence shall be captured only from the in-app camera; gallery import is disabled for all users. |
| FR-008.3 | The app shall record for each image: capture time, device ID, user ID, meter ID, transaction ID and GPS (if available). These are sent as **JSON metadata** with the upload (the server is the record); EXIF is not relied on. A visible watermark overlay is optional (`WatermarkEnabled`). |
| FR-008.4 | The app shall run a quality check before acceptance: blur (variance of Laplacian below `BlurThreshold`), over/under-exposure (mean luminance outside `ExposureMin`–`ExposureMax`) and minimum resolution. A failed check shall prompt a retake, overridable with a reason from the `QualityOverrideReason` LOV; overrides are flagged `QUALITY_OVERRIDE`. |
| FR-008.5 | Images shall be compressed to JPEG within a configurable maximum (`ImageLongEdgePx` default 1,600; `ImageMaxKb` default 500). |
| FR-008.6 | Images shall be written directly to the app's encrypted private storage, never the shared gallery, and deleted after the server confirms receipt. |
| FR-008.7 | Each image has a **role** from the `ImageRole` LOV: `DISPLAY`, `CONTEXT`, `OBSTRUCTION`, `DAMAGE`, `OLD_METER_FINAL`, `NEW_METER`. Minimum roles per status are in §6.1; the maximum per reading is `MaxImagesPerReading` (default 4). |

### 5.2 Image metadata and integrity (FR-009)

FR-009.1 — For every image the system shall store transaction ID, meter ID, image ID, role, capture
timestamp (device), received timestamp (server), user ID, device ID, SHA-256 hash, size, format,
width, height, and — for the image OCR was run on — OCR result and confidence.

FR-009.2 — The hash shall be computed on the device over the **final compressed bytes exactly as
uploaded**, and verified by the API; a mismatch rejects the submission with `IMAGE_HASH_MISMATCH`.

FR-009.3 — Images shall be stored in a private Blob container at
`readings/{yyyy}/{MM}/{meterId}/{transactionId}/{imageId}.jpg` and never overwritten
(blob versioning on; delete only through retention policy).

FR-009.4 — Images shall be served to the portal only through the API (streamed or short-lived
read-only SAS ≤ 5 minutes), after a scope check.

### 5.3 OCR process (FR-010)

1. Reader captures the `DISPLAY` photo inside the framing guide.
2. OCR runs on the cropped display region (on-device; cloud fallback only if `OcrCloudFallbackEnabled`).
3. The app shows the detected reading and confidence, e.g. 52,500 at 98.7%.
4. Reader taps **Use Reading** or **Retake**, or types the value manually.
5. The app sends the OCR value, confidence, engine, engine version and the confirmed value.
6. The server validates the confirmed value and records any difference between OCR and confirmed reading.

### 5.4 OCR rules (BR-OCR)

- **BR-OCR-01** — OCR shall never be the authoritative reading.
- **BR-OCR-02** — The reader shall explicitly confirm or correct every OCR result.
- **BR-OCR-03** — Below `OcrPrefillThreshold` (default 0.85) the app shall not pre-fill the field; it shows the suggestion only.
- **BR-OCR-04** — When the confirmed reading differs from an OCR reading with confidence ≥ `OcrMismatchThreshold` (default 0.95), the server shall flag `OCR_MISMATCH` for supervisor sampling (not an exception).
- **BR-OCR-05** — OCR engine, version and confidence shall be stored for audit and accuracy reporting.
- **BR-OCR-06** — Readings where the OCR value was accepted unchanged shall be included in supervisor random sampling (`OcrAcceptSamplePercent`, default 2%), because pre-filled values tend to be accepted without checking.
- **BR-OCR-07** — If the engine does not return a calibrated confidence, the app shall derive one (minimum element confidence over the digit string) and the thresholds shall be re-calibrated from pilot data before OCR is enabled for all readers.

### 5.5 OCR engine options

| Option | Strengths | Limits |
|---|---|---|
| On-device (Google ML Kit text recognition or a custom TensorFlow Lite model) | Works offline, no data leaves the device, instant result | Generic text models struggle with rolling-digit wheels; custom model needs training data |
| Azure AI Vision Read API | Strong general accuracy, no model maintenance | Needs connectivity, per-call cost, image leaves device (via the API, never direct); region must meet data-residency policy |
| Custom vision model (Phase 6) | Can detect display region, meter number, condition and quality | Needs a labelled image set from Phase 2–5 field photos |

Recommendation: start with on-device ML Kit plus a digit-region crop, and collect confirmed
photo/value pairs from day one to train a custom model later. Cloud fallback is **off** by default.

---

## 6. Meter status business rules

The meter status chosen by the reader decides which fields are mandatory and how the server
derives billing consumption. The list is an administrator-controlled LOV; the business confirms
the final values. Field rules per status are stored as configuration (`StatusRules`) so they can
change without a release.

### 6.1 Status rules summary

| Status | New reading | Minimum photos (roles) | Reason | Remarks | Server outcome | Billing consumption |
|---|---|---|---|---|---|---|
| WORKING | Mandatory | 1 `DISPLAY` (configurable) | — | Optional | Accepted, unless a validation rule raises an exception (§7.3) | New − previous (or rollover) — `ACTUAL` |
| DAMAGED | Optional if unreadable | 1 `DAMAGE` | Mandatory (`DamageReason` LOV) | Mandatory | **Exception** | Actual if readable and supervisor approves actual, else average |
| SUBMERSED | Optional | 1 `CONTEXT` | — | Mandatory | Accepted with average; **Exception** when submersed for `SubmersionEscalationPeriods` (default 2) consecutive periods | Average (a reading, if given, is stored but not billed) |
| FIRST_READING (was NEW_METER) | Mandatory | 1 `DISPLAY` | — | Optional | Accepted, unless validation raises an exception | New − opening reading |
| METER_REPLACED | Old final + new opening + new current mandatory | 1 `OLD_METER_FINAL` + 1 `NEW_METER` | Mandatory (`ReplacementReason` LOV) | Optional | **Exception** (approval required) | (Old final − old previous) + (new current − new opening) — `SPLIT` |
| NOT_ACCESSIBLE | Not captured | 1 `OBSTRUCTION` | Mandatory (`AccessReason` LOV) | Mandatory | Revisit task created; assignment becomes `Revisit` | Average only if still unread at period close |
| REMOVED | Final reading if available | 1 `CONTEXT` | Mandatory | Mandatory | **Exception** (approval required) | Final − previous; average if no final reading; meter deactivated after approval |

### 6.2 Working meter (BR-001)

- New reading is mandatory.
- Consumption = new reading − previous reading. Example: 52,500 − 50,000 = 2,500.
- A reading lower than previous is either a rollover (BR-006) or an **exception** `LOWER_THAN_PREVIOUS` for supervisor review. It is **not** a hard rejection; the reader's evidence decides.

### 6.3 Damaged meter (BR-002)

- Status, damage photo, damage reason and remarks are mandatory; the reading is optional if the display is unreadable.
- Always an exception. The supervisor approves either the actual reading (if provided and credible) or average consumption.
- The server or billing engine calculates average consumption (BR-007); the app never calculates it.
- A maintenance referral (`MaintenanceReferral` record) shall be raised for the meter and appear in the portal's referral list and export (INT-006).

### 6.4 Submersed meter (BR-003)

- Status, photo and remarks are mandatory.
- Billing uses average consumption calculated by the server.
- Submersion for `SubmersionEscalationPeriods` consecutive periods shall raise an exception `REPEATED_SUBMERSION`.

### 6.5 First reading of a new meter (BR-004)

- Applies to an active meter with **no previous approved reading**. The app selects `FIRST_READING` automatically for such meters; the reader cannot choose it for other meters.
- Previous reading is the meter's `OpeningReading` from master data (default 0 when unknown).
- Consumption = new reading − opening reading.
- Photograph is mandatory. The meter must already exist in master data, created by an administrator or via an approved replacement transaction (BR-005).

### 6.6 Meter replacement (BR-005)

A replacement creates one formal Meter Replacement Transaction with two parts:

| Old meter | New meter |
|---|---|
| Meter number (from assignment) | Meter number (typed; must not exist as an active meter) |
| Final reading | Opening reading |
| Replacement date | Current reading |
| Photo of final reading | Photo of new meter |
| | Register digits, meter type (defaults to old meter's) |
| | Maintenance work order reference (if replaced by a separate team) |

- The submission is always an exception. On supervisor approval the server: sets the old meter's `RemovalDate` and deactivates it; **creates** the new meter (if not already in master data) on the same property with the captured opening reading; links both through `MeterReplacement`; and transfers the current assignment to the new meter.
- On rejection nothing changes in master data; the old meter's assignment returns to `Rejected` for re-capture.
- Consumption for the period is split across old and new meters and sent to billing as **two lines** linked by the replacement ID (INT-003).
- If the new meter already exists in master data (pre-loaded by admin), the reader selects it instead of typing.

### 6.7 Rollover (BR-006)

When a meter register passes its maximum (e.g. 99,999 → 00,120), the server shall compute
consumption as `(RegisterMax − previous + 1) + new`.

A lower reading is a **rollover candidate** only when `previous ≥ RegisterMax × (1 − RolloverProximityPct/100)`
(default proximity 10%). A rollover candidate is accepted (flagged `ROLLOVER`) when the result is
within the expected range (BR-008); otherwise it becomes exception `ROLLOVER_OUT_OF_RANGE`. A lower
reading that is not a rollover candidate is exception `LOWER_THAN_PREVIOUS`.

### 6.8 Not accessible (BR-009)

- The reader records reason, photo of obstruction and remarks; no reading.
- The server creates a `RevisitTask` and sets the assignment to `Revisit`. The revisit is assigned to the same reader by default; the supervisor may reassign it.
- Up to `MaxRevisits` (default 1) revisits per period. If the meter is still unread when the period closes, the worker posts average consumption with basis `AVERAGE` and flag `ESTIMATED_NO_ACCESS`.
- `NotAccessibleEscalationPeriods` (default 2) consecutive periods without access raise exception `REPEATED_NO_ACCESS` for supervisor action.

### 6.9 Removed meter (BR-010)

- Final reading if available, mandatory photo, reason and remarks. Always an exception.
- On approval the meter is deactivated (`RemovalDate` set) and no future assignments are generated for it.

### 6.10 Average consumption (BR-007) `[ASSUMPTION — confirm with business and billing]`

- Average = arithmetic mean of the billing consumption of the last `AveragePeriods` (default 3) periods whose basis was `ACTUAL`, `ROLLOVER` or `SPLIT`.
- If fewer than `AveragePeriods` such periods exist, use those available (minimum 1).
- If none exist, use `DefaultAverageConsumption` configured per meter type; if that is not configured, raise exception `NO_AVERAGE_BASIS`.
- The average is rounded to the meter's `DecimalDigits`.
- Seasonal averaging (same period last year) may be added later as a configurable method.

### 6.11 Catch-up after estimated periods (BR-011) `[ASSUMPTION — confirm with billing]`

When an actual reading follows one or more periods billed on `AVERAGE`, the server sends billing
the **actual consumption since the last actual reading** together with the list of estimated
periods and their billed averages. Billing performs any true-up/credit. (Alternative under
decision: the server sends `actual since last actual − sum of averages billed`, see OD-07.)

### 6.12 Expected range (BR-008)

For each meter and period the server computes and sends to the app:

- `ExpectedLow` = 0 (a zero consumption is allowed but flagged `ZERO_CONSUMPTION`).
- `ExpectedHigh` = max(`Average × HighConsumptionFactor`, `HighConsumptionFloor`[meter type]) — defaults factor 3.0, floor 100 m³ `[ASSUMPTION]`.
- Consumption above `ExpectedHigh` → exception `HIGH_CONSUMPTION`.
- Zero consumption for `ZeroConsumptionEscalationPeriods` (default 3) consecutive periods → exception `REPEATED_ZERO`.

---

## 7. Reading lifecycle and server validation

### 7.1 State models

There are three separate states. Code shall use exactly these names.

**A. Device transaction state** (app only)

```
DRAFT → QUEUED → SENDING → DELIVERED          (server returned Accepted / Exception / Rejected)
                    └────→ FAILED → QUEUED    (network/5xx/timeout; retry with same Transaction ID)
```

**B. Server reading status** (`ReadingTransaction.Status`)

```
                ┌─▶ ACCEPTED ──────────────────────────────┐
 submission ────┼─▶ EXCEPTION ─▶ APPROVED ─────────────────┼─▶ POSTED ─▶ ACKNOWLEDGED
                │        └────▶ REJECTED_BY_SUPERVISOR     │        └──▶ BILLING_FAILED ─▶ (re-send) POSTED
                └─▶ (validation rejection: HTTP 422, no reading row; audit only)
 ACCEPTED/APPROVED/POSTED/ACKNOWLEDGED ─▶ SUPERSEDED (by an admin correction, §8.5)
```

**C. Assignment status** (`Assignment.Status`, one row per meter per period)

| Status | Meaning | Set when |
|---|---|---|
| Pending | Not yet captured | Period opens / reassignment |
| Queued | Captured on device, not yet delivered | App-only display state |
| Submitted | Server holds an ACCEPTED reading awaiting billing | Accepted |
| Exception | Reading held for supervisor | Exception raised |
| Revisit | Not accessible; revisit outstanding | NOT_ACCESSIBLE |
| Rejected | Rejected by server or supervisor; re-capture required | Rejection |
| Completed | Reading posted/acknowledged by billing, or average posted at close | Billing ack / period close |

### 7.2 Validation order and outcomes (BR-012)

The server shall evaluate a submission in this order and stop at the first **rejection**.
Rejections return HTTP 4xx with a problem-details `code` (Appendix A); nothing is stored except an
audit entry.

| # | Check | Failure → |
|---|---|---|
| 1 | Token valid, role `MeterReader` | 401/403 |
| 2 | Device registered and active; app version ≥ `MinAppVersion` | `DEVICE_REVOKED`, `APP_VERSION_UNSUPPORTED` |
| 3 | Transaction ID already processed → return the stored response (idempotent); same ID with a different payload hash | `TRANSACTION_ID_REUSED` (409) |
| 4 | Payload schema and field formats | `VALIDATION_FAILED` (422, field errors) |
| 5 | Period exists and is open | `PERIOD_CLOSED` |
| 6 | Meter was assigned to the caller **at `CapturedAt`** (supports offline reassignment races) | `METER_NOT_ASSIGNED` |
| 7 | Assignment not already Submitted/Exception/Completed by another accepted reading | `ALREADY_READ` (409) |
| 8 | `CapturedAt` not more than `ClockSkewToleranceMinutes` (5) in the future and not older than `MaxQueueAgeDays` (7) | `CAPTURE_TIME_INVALID` |
| 9 | Mandatory fields and minimum image roles for the status (§6.1); LOV codes active | `MANDATORY_FIELD_MISSING`, `INVALID_LOV_CODE` |
| 10 | Reading digits ≤ register size | `READING_EXCEEDS_REGISTER` |
| 10a | Tenant checked by the reader and still a current tenant of the property in vw_MR_Tenant (FR-006.12) | `NO_TENANT`, `TENANT_NOT_CONFIRMED`, `TENANT_CHANGED` |
| 11 | Image count ≤ max; each image hash, size and format valid | `IMAGE_HASH_MISMATCH`, `IMAGE_INVALID` |
| 12 | Status-specific consistency (e.g. FIRST_READING only for meters without history; replacement new meter number not active elsewhere) | `STATUS_NOT_ALLOWED`, `METER_NUMBER_IN_USE` |

If all checks pass, the server stores the reading, computes consumption and billing consumption
(§6), then evaluates **exception rules** and **flags**:

| Exception code (holds billing) | Rule |
|---|---|
| LOWER_THAN_PREVIOUS | BR-006 |
| ROLLOVER_OUT_OF_RANGE | BR-006 |
| HIGH_CONSUMPTION | BR-008 |
| REPEATED_ZERO | BR-008 |
| DAMAGED_METER | BR-002 (always) |
| REPEATED_SUBMERSION | BR-003 |
| REPEATED_NO_ACCESS | BR-009 |
| METER_REPLACEMENT | BR-005 (always) |
| METER_REMOVAL | BR-010 (always) |
| NO_AVERAGE_BASIS | BR-007 |

| Flag code (does not hold billing) | Rule |
|---|---|
| OCR_MISMATCH | BR-OCR-04 |
| OCR_SAMPLED | BR-OCR-06 |
| ROLLOVER | BR-006 accepted rollover |
| ZERO_CONSUMPTION | BR-008 |
| QUALITY_OVERRIDE | FR-008.4 |
| GPS_DISTANT | Captured > `GpsDistanceFlagMeters` (200) from property coordinates, when both exist |
| NO_GPS | No location supplied |
| LATE_SYNC | Received > `QueueWarningHours` after capture |
| ESTIMATED_NO_ACCESS | BR-009 |

A reading with ≥ 1 exception has status `EXCEPTION`; otherwise `ACCEPTED`. All raised exceptions
and flags are stored as `ReadingFlag` rows.

### 7.3 Idempotency (BR-013)

- The Transaction ID is the primary key of `ReadingTransaction`.
- The server stores a SHA-256 of the canonical JSON payload. A repeat with the same ID and hash returns the original HTTP status and body; with a different hash it returns `TRANSACTION_ID_REUSED`.
- Validation rejections are also remembered by Transaction ID for `MaxQueueAgeDays`, so a retry gets the same rejection.

### 7.4 Reading periods (BR-014)

- Periods are monthly (`yyyy-MM`) with `ReadingWindowStart`, `ReadingWindowEnd`, and `Status` (`Planned`, `Open`, `Closed`).
- Exactly one period is `Open` at a time `[ASSUMPTION]`.
- On opening a period the worker generates one `Assignment` per active meter, using each zone's default reader (`Zone.DefaultReaderUserId`, overridable per property via `Property.DefaultReaderUserId`).
- Before closing, the portal shows: unread meters, open exceptions, open revisits and devices that have not synced since the window ended. An admin may close only after confirming. On close the worker: posts averages for unread/not-accessible meters (`AVERAGE`, flag `ESTIMATED_NO_ACCESS` or `ESTIMATED_UNREAD`); leaves open exceptions open (they still post when decided, against their own period).
- One billable reading per meter per period. A later re-capture (after rejection or revisit) supersedes nothing — the earlier one was never billable.

---

## 8. Supervisor exception workflow

### 8.1 Exception queue (FR-011)

| ID | Requirement |
|---|---|
| FR-011.1 | Supervisors shall see a queue of `EXCEPTION` readings for their team, filterable by zone, reader, exception code, period and age; sorted oldest first. |
| FR-011.2 | The detail view shall show meter, property, previous 6 periods' readings and consumption, the submitted values, OCR value and confidence, all images (zoomable), GPS on a map when available, reasons, remarks, flags and the audit history. |
| FR-011.3 | Supervisors shall **Approve** (choosing billing basis `ACTUAL` or `AVERAGE` where both are valid) or **Reject** (mandatory reason from `RejectReason` LOV, optional comment). |
| FR-011.4 | Reject shall set the assignment to `Rejected` and notify the reader in the app at next sync; the reader re-captures with a new Transaction ID. |
| FR-011.5 | Approve shall move the reading to `APPROVED` and enqueue it for billing; for replacement/removal it applies the master-data changes in the same database transaction (BR-005, BR-010). |
| FR-011.6 | Bulk approve shall be allowed only for exception codes in `BulkApprovableCodes` (default: `REPEATED_SUBMERSION`, `HIGH_CONSUMPTION`). |
| FR-011.7 | Exceptions older than `ExceptionSlaHours` (default 48) shall be highlighted and listed on the admin dashboard. |
| FR-011.8 | A supervisor shall not approve or reject a reading they submitted themselves. |

### 8.2 Flag sampling (FR-012)

The portal shall show flagged-but-accepted readings (`OCR_MISMATCH`, `OCR_SAMPLED`, `GPS_DISTANT`,
`QUALITY_OVERRIDE`) for review. Marking one "Incorrect" opens the correction process (§8.5).

### 8.3 Reassignment (FR-013)

Supervisors may reassign `Pending`, `Rejected` or `Revisit` assignments for the current period, at
meter, property or zone level, to another reader in their team. Readers receive changes at next
sync. Readings captured before reassignment remain valid (§7.2 check 6).

### 8.4 Progress monitoring (FR-014)

Dashboard per period by zone, team and reader: assigned, completed, pending, exceptions, revisits,
readings per hour, last sync per reader/device.

### 8.5 Correction of an approved reading (FR-015)

- Only administrators may correct. A correction creates a **new** `ReadingTransaction` (server-generated ID, `CorrectsTransactionId` set) with the corrected value, a mandatory reason and reference; the original becomes `SUPERSEDED`. Nothing is edited in place.
- If the original was already posted to billing, the outbox sends a **reversal** of the original line and the corrected line (INT-004).
- Corrections require the reading's images to remain; no new photo is needed.

---

### 4.9 Find a Property (FR-021)

| ID | Requirement |
|---|---|
| FR-021.1 | A search bar shall be shown on Home (all assigned zones) and on each zone's property list (that zone only). |
| FR-021.2 | Search shall match property code, property name and meter number, ignoring case, spaces and dashes, anywhere in the text ("149" finds 1499-W1 and 1497). |
| FR-021.3 | Input shall be by an in-app keypad (digits, dash, delete, clear), an ABC switch to the letter keyboard for codes such as W1, or voice (speech-to-text). |
| FR-021.4 | Results shall be grouped into buildings and meters, with the matched part highlighted, and update on every key press. |
| FR-021.5 | Filters: All / To read / Done (with counts) and meter type (irrigation, sewerage). |
| FR-021.6 | With no text, the screen lists everything in scope, so it also works as a filtered browse. Search works offline on cached assignments. |

### 4.10 My summary — reading reconciliation (FR-022)

| ID | Requirement |
|---|---|
| FR-022.1 | The reader shall see, for the open period: total meters assigned, meters read on the phone, and readings uploaded to the server. |
| FR-022.2 | Each meter shall be in exactly one bucket — accepted, being checked, read again, visit again, waiting to upload, not read — and the buckets shall add up to the total. Shown as a stacked bar with a labelled legend. |
| FR-022.3 | When read ≠ uploaded, the uploaded count shall be highlighted and an **Upload now** button shown; a per-zone table shall show where the difference is. |
| FR-022.4 | The screen shall show the time of the last successful upload. |
| FR-022.5 | The supervisor portal shall show the same reconciliation per reader and device, plus server-to-billing (posted, acknowledged, failed) per INT-009. |

## 9. Offline operation and synchronisation

| ID | Requirement |
|---|---|
| FR-020.1 | The app shall store assignments, meters, properties, zones, LOVs, status rules and settings in an encrypted local database (Room + SQLCipher; key held in Android Keystore). |
| FR-020.2 | Initial sync at sign-in downloads all assignments for the open period. Delta sync (`GET /sync/assignments?since=<token>`) runs on app open, after each submission, and every `SyncIntervalMinutes` (default 30) when online, via WorkManager. |
| FR-020.3 | Delta responses include added, changed and **removed** assignments (reassigned away), and reading outcomes for the reader's earlier submissions (e.g. supervisor rejections). |
| FR-020.4 | Submissions are written to a local outbox inside one local DB transaction with their images, then sent by a WorkManager job with network constraint and exponential backoff (30 s → max 30 min). |
| FR-020.5 | Items are sent oldest first; one failing item shall not block the others. HTTP 4xx (other than 408/429) is final (`DELIVERED` with rejection); 408, 429, 5xx and network errors are `FAILED` and retried. |
| FR-020.6 | If an assignment is reassigned away while a capture for it is queued, the capture is still sent; the server decides by capture time (§7.2 check 6). |
| FR-020.7 | Items older than `QueueWarningHours` trigger a dashboard warning; at `MaxQueueAgeDays` the app shall stop retrying and show the item as "Needs supervisor" (server would reject it). |
| FR-020.8 | One active user per device. A different user cannot sign in while the previous user's outbox is not empty; the app shows the count and asks the previous user to sync. An administrator can authorise a purge from the portal (`POST /devices/{id}/purge-queue`), which is audited. |
| FR-020.9 | The LOV, status rules and settings shall be versioned; the app refreshes them when the server's version differs (`X-Config-Version` response header). |
| FR-020.10 | The app shall work with no connectivity for a full working day (≥ 300 captures with 2 images each) without degrading. |

---

## 10. Web portal

| ID | Requirement |
|---|---|
| FR-016.1 | The portal shall sign users in with Entra ID (OIDC) and use the same role and scope rules as the API (it calls the API or the same application layer — no separate authorisation logic). |
| FR-016.2 | Phase 4 (minimum): exception queue and decisions (§8.1), reassignment (§8.3), progress dashboard (§8.4), period close checklist. |
| FR-016.3 | Phase 6 (full): master-data management (§11), LOV and settings editors, device management, flag sampling, corrections, audit search, reports, maintenance referral list. |
| FR-016.4 | Reports (CSV export for all): completion by zone/reader, exceptions by code and age, OCR accuracy (OCR vs confirmed by engine version), reader productivity, estimated (average) meters, maintenance referrals, billing reconciliation (posted vs acknowledged vs failed). |
| FR-016.5 | Every list shall support server-side paging, sorting and filtering. |

---

## 11. Master data and configuration

### 11.1 Master data (FR-017)

| ID | Requirement |
|---|---|
| FR-017.1 | Administrators shall create, edit and deactivate zones, properties and meters in the portal. Records are never hard-deleted. |
| FR-017.2 | Bulk import from CSV shall be supported for zones, properties and meters, with a validation preview (row-level errors) before commit, and an import log. |
| FR-017.3 | Meter attributes: number, type, property, register digits, decimal digits, opening reading, install date, removal date, status, serial (optional), latitude/longitude (optional). |
| FR-017.4 | Changing a meter's property or register size while it has an assignment in the open period shall be blocked. |
| FR-017.5 | Source of master data `[OPEN — OD-01]`: if billing becomes the master, INT-005 replaces manual maintenance and the portal becomes read-only for those fields. |

### 11.2 LOVs (FR-018)

LOV categories: `MeterStatus`, `DamageReason`, `AccessReason`, `ReplacementReason`,
`RemovalReason`, `RejectReason`, `QualityOverrideReason`, `ImageRole`, `CorrectionReason`.
Each item: code (immutable), English label, Arabic label `[ASSUMPTION — OD-09]`, active flag,
sort order. Codes referenced by business rules (e.g. `WORKING`) are seeded and cannot be deactivated.

### 11.3 Settings (FR-019)

All thresholds in this document are settings with a key, value, type, default and description,
editable by administrators with audit. Initial set:

| Key | Default | Used by |
|---|---|---|
| IdleTimeoutMinutes | 15 | FR-001.5 |
| OfflineGraceHours | 24 | FR-001.6 |
| SyncIntervalMinutes | 30 | FR-020.2 |
| QueueWarningHours | 24 | FR-003.2, FR-020.7 |
| MaxQueueAgeDays | 7 | §7.2, FR-020.7 |
| ClockSkewToleranceMinutes | 5 | §7.2 |
| MinAppVersion | 1.0.0 | FR-002.6 |
| RequirePlayIntegrity | true | FR-002.5 |
| PhotoMandatoryWorking | true | §6.1 |
| MaxImagesPerReading | 4 | FR-008.7 |
| ImageLongEdgePx | 1600 | FR-008.5 |
| ImageMaxKb | 500 | FR-008.5 |
| BlurThreshold / ExposureMin / ExposureMax | 100 / 40 / 220 | FR-008.4 (calibrate in pilot) |
| WatermarkEnabled | false | FR-008.3 |
| OcrEnabled | false (Phase 3: true) | §5.3 |
| OcrCloudFallbackEnabled | false | §5.3 |
| OcrPrefillThreshold | 0.85 | BR-OCR-03 |
| OcrMismatchThreshold | 0.95 | BR-OCR-04 |
| OcrAcceptSamplePercent | 2 | BR-OCR-06 |
| AveragePeriods | 3 | BR-007 |
| DefaultAverageConsumption.{Irrigation,Sewerage} | not set | BR-007 |
| HighConsumptionFactor | 3.0 | BR-008 |
| HighConsumptionFloor.{Irrigation,Sewerage} | 100 | BR-008 |
| ZeroConsumptionEscalationPeriods | 3 | BR-008 |
| RolloverProximityPct | 10 | BR-006 |
| SubmersionEscalationPeriods | 2 | BR-003 |
| MaxRevisits | 1 | BR-009 |
| NotAccessibleEscalationPeriods | 2 | BR-009 |
| GpsDistanceFlagMeters | 200 | §7.2 |
| ExceptionSlaHours | 48 | FR-011.7 |
| BulkApprovableCodes | REPEATED_SUBMERSION,HIGH_CONSUMPTION | FR-011.6 |
| BillingMaxAttempts | 10 | INT-002 |

---

## 12. Billing integration

The billing system's interface is **not yet known** (OD-02). The design isolates it behind an
adapter so only the adapter changes once it is.

| ID | Requirement |
|---|---|
| INT-001 | Readings in `ACCEPTED` or `APPROVED` status, and averages posted at period close, shall be written to a `BillingOutbox` row **in the same database transaction** that sets the status (transactional outbox). |
| INT-002 | The worker shall dispatch outbox rows through an `IBillingAdapter` in creation order per meter, retrying transient failures with exponential backoff up to `BillingMaxAttempts`, then marking `BILLING_FAILED` and alerting. Admins may re-send from the portal. |
| INT-003 | Each billing line shall contain at least: line ID (UUID, idempotency key for billing), transaction ID, period, property code, meter number, meter type, previous reading and date, new reading and date, consumption, billing consumption, consumption basis, estimated-periods list (BR-011), replacement ID (for split lines), reader ID, captured-at. |
| INT-004 | Corrections shall be sent as a reversal line (negating the original line ID) followed by a new line. |
| INT-005 | (Optional, OD-01) Master-data feed from billing: properties and meters, nightly or on change, via `POST /api/v1/integration/master-data` (Integration role) or a pull adapter. |
| INT-006 | Maintenance referrals (damaged meters) shall be exportable as CSV and available via `GET /api/v1/integration/maintenance-referrals?since=`; no work orders are created by this system. |
| INT-007 | Billing acknowledgements: synchronous response, or asynchronous callback to `POST /api/v1/integration/billing/acks` (Integration role) with `{lineId, status: ACCEPTED|REJECTED, billingReference, message}`. Rejected lines set `BILLING_FAILED` and appear in the reconciliation report. |
| INT-008 | Adapters to provide: `RestBillingAdapter` (default), `FileDropBillingAdapter` (CSV to SFTP/Blob) and `FakeBillingAdapter` for test environments. Selection by configuration. |
| INT-009 | A daily reconciliation job shall compare posted lines with acknowledgements and report lines unacknowledged for more than 24 h. |

---

## 13. Data model

Schema is owned by **EF Core migrations** in the backend; no hand-written DDL for these tables.
All tables in schema `mr`. Every table has `CreatedAt`, `CreatedBy`, `UpdatedAt`, `UpdatedBy`
(UTC `datetime2`) and `RowVersion` (`rowversion`) for optimistic concurrency, unless stated.

| Table | Key columns (non-exhaustive) | Notes |
|---|---|---|
| Zone | Id, Code (unique), Name, DefaultReaderUserId?, IsActive | |
| Property | Id, ZoneId, Code (unique), Name, RouteSequence, Latitude?, Longitude?, DefaultReaderUserId?, IsActive | |
| Meter | Id, PropertyId, MeterNumber (unique among active), MeterType (`Irrigation`/`Sewerage`), RegisterDigits, DecimalDigits, OpeningReading, InstallDate, RemovalDate?, Status (`Active`/`Inactive`), Serial?, Latitude?, Longitude? | Filtered unique index on MeterNumber where Status = Active |
| Team | Id, Name, SupervisorUserId | |
| AppUser | Id, EntraObjectId (unique), DisplayName, Email, TeamId?, IsActive, LastSeenAt | Roles come from token, not stored |
| ReadingPeriod | Id, Code (`yyyy-MM`, unique), ReadingWindowStart, ReadingWindowEnd, Status, OpenedAt, ClosedAt, ClosedBy | Filtered unique: one `Open` |
| Assignment | Id, PeriodId, MeterId, ReaderUserId, Status, CurrentTransactionId?, ValidFrom, ValidTo? | Unique (PeriodId, MeterId) for the current row; history rows keep ValidFrom/ValidTo for capture-time checks |
| Device | Id (client UUID), LastUserId, Model, AndroidVersion, AppVersion, RegisteredAt, LastSyncAt, LastIntegrityCheckAt, Status, RevokedAt?, RevokedBy? | |
| ReadingTransaction | Id (Transaction ID), PeriodId, MeterId, AssignmentId, UserId, DeviceId, MeterStatusCode, ReasonCode?, Remarks?, NewReading?, PreviousReading, PreviousReadingDate, Consumption?, BillingConsumption?, ConsumptionBasis, OcrValue?, OcrConfidence?, OcrEngine?, OcrEngineVersion?, CapturedAt, ReceivedAt, Latitude?, Longitude?, GpsAccuracyM?, Status, PayloadHash, ResponseJson, CorrectsTransactionId?, ReplacementId? | `decimal(18,3)` for all reading values |
| ReadingRejection | TransactionId (PK), DeviceId, UserId, Code, ResponseJson, ReceivedAt | Idempotent rejections (§7.3); purge after MaxQueueAgeDays |
| ReadingImage | Id (client UUID), TransactionId, Role, BlobPath, Sha256, SizeBytes, Format, Width, Height, CapturedAt, ReceivedAt, IsOcrSource | |
| ReadingFlag | Id, TransactionId, Code, Kind (`Exception`/`Flag`), Detail | |
| ExceptionDecision | Id, TransactionId, Decision (`Approved`/`Rejected`), BillingBasis?, ReasonCode?, Comment?, DecidedBy, DecidedAt | |
| MeterReplacement | Id, OldMeterId, NewMeterId, TransactionId, OldFinalReading, NewOpeningReading, NewCurrentReading, ReplacementDate, WorkOrderRef?, Status | |
| RevisitTask | Id, AssignmentId, OriginTransactionId, AssignedUserId, Status, Attempt | |
| MaintenanceReferral | Id, MeterId, TransactionId, ReasonCode, Status, ExportedAt? | |
| LovItem | Id, Category, Code, LabelEn, LabelAr, IsActive, IsSystem, SortOrder | Unique (Category, Code) |
| Setting | Key (PK), Value, ValueType, Description | |
| StatusRule | MeterStatusCode (PK), RulesJson | Mandatory fields and minimum image roles per status |
| ConfigVersion | Id, Version, ChangedAt | Bumped on LOV/settings/status-rule change |
| BillingOutbox | Id (line ID), TransactionId, Kind (`Line`/`Reversal`), PayloadJson, Status, Attempts, NextAttemptAt, LastError, SentAt?, AckStatus?, BillingReference?, AckAt? | |
| AuditLog | Id (bigint), At, UserId?, ClientId?, Action, EntityType, EntityId, BeforeJson?, AfterJson?, CorrelationId, IpAddress?, DeviceId? | Append-only: application login has INSERT only; no UPDATE/DELETE |

Seed data required by code (LOV system codes, settings defaults, status rules) shall be applied by
an idempotent seeding step at deployment, not hard-coded in queries.

---

## 14. API specification

Base path `/api/v1`. JSON (camelCase), UTC ISO-8601 timestamps, decimals as JSON numbers.
Errors use RFC 9457 problem details with an extra `code` (Appendix A) and `errors` for field
validation. Every response carries `X-Correlation-Id`. An OpenAPI document shall be generated and
published with each build and is the contract for the Android client.

### 14.1 Mobile endpoints (role `MeterReader` unless noted)

| Method & path | Purpose |
|---|---|
| `GET /me` | Profile, roles, team, open period (any role) |
| `POST /devices/register` | Register/refresh device; body includes Play Integrity token |
| `GET /config` | LOVs, status rules, settings relevant to the app, `configVersion` |
| `GET /sync/assignments?since={token}` | Assignment delta for open period: zones, properties, meters, assignments (with previous reading, ExpectedLow/High), removals, outcomes; returns next `syncToken` |
| `POST /readings` | Submit one reading (JSON, with `photoCount`). Returns `{transactionId, meterId, status, state, consumption, exceptions[]}` with 201 (new) or 200 (idempotent repeat) |
| `PUT /readings/{transactionId}/images/{imageId}?role=&capturedAtUtc=` | Upload one photo after its reading: body `image/jpeg`, header `X-Content-SHA256`. 201 new, 200 repeat. Decided 2026-10-04 instead of one multipart request, so a reading is never held up by its photos |
| `GET /readings/mine?period=&status=&page=` | Own submissions |
| `GET /readings/{transactionId}` | One own submission and its outcome |

`reading` JSON (example):

```json
{
  "transactionId": "7d3c0f0e-2b0a-4c41-9a2f-5d1f0b6c7a11",
  "meterId": 1234,
  "periodCode": "2026-10",
  "meterStatusCode": "WORKING",
  "newReading": 52500,
  "reasonCode": null,
  "remarks": null,
  "capturedAt": "2026-10-04T07:15:22Z",
  "location": { "lat": 25.0, "lon": 55.1, "accuracyM": 8.5 },
  "ocr": { "value": 52500, "confidence": 0.987, "engine": "mlkit-text-v2", "engineVersion": "19.0.1", "imageId": "a1…" },
  "qualityOverrideReasonCode": null,
  "replacement": null,
  "images": [
    { "imageId": "a1…", "role": "DISPLAY", "sha256": "…", "sizeBytes": 312000, "format": "jpeg",
      "width": 1600, "height": 1200, "capturedAt": "2026-10-04T07:15:10Z" }
  ]
}
```

`replacement` (METER_REPLACED only): `{ "oldFinalReading", "replacementDate", "newMeterNumber" | "newMeterId", "newRegisterDigits", "newOpeningReading", "newCurrentReading", "workOrderRef" }`.

### 14.2 Supervisor / admin endpoints

| Method & path | Role |
|---|---|
| `GET /exceptions?zone=&reader=&code=&period=&page=` | Supervisor, Admin |
| `GET /readings/{id}/detail` (history, images, flags, audit) | Supervisor, Admin |
| `GET /readings/{id}/images/{imageId}` (stream or SAS redirect) | Reader (own), Supervisor, Admin |
| `POST /exceptions/{transactionId}/approve` `{billingBasis, comment}` | Supervisor, Admin |
| `POST /exceptions/{transactionId}/reject` `{reasonCode, comment}` | Supervisor, Admin |
| `POST /exceptions/bulk-approve` `{transactionIds[]}` | Supervisor, Admin |
| `GET /flags?code=&period=` | Supervisor, Admin |
| `POST /assignments/reassign` `{scope: meter|property|zone, ids[], toUserId}` | Supervisor, Admin |
| `GET /progress?period=&groupBy=zone|team|reader` | Supervisor, Admin |
| `POST /readings/{transactionId}/corrections` `{newReading, reasonCode, reference}` | Admin |
| CRUD `/zones`, `/properties`, `/meters`, `/teams`, `/users/{id}` (team mapping) | Admin |
| `POST /imports/{entity}?dryRun=true|false` (CSV) | Admin |
| `GET/PUT /lov/{category}`, `GET/PUT /settings`, `GET/PUT /status-rules` | Admin |
| `GET /devices`, `POST /devices/{id}/revoke`, `/reinstate`, `/purge-queue` | Admin |
| `POST /periods`, `POST /periods/{id}/open`, `GET /periods/{id}/close-check`, `POST /periods/{id}/close` | Admin |
| `GET /billing/outbox?status=`, `POST /billing/outbox/{id}/resend` | Admin |
| `GET /audit?entityType=&entityId=&user=&from=&to=` | Supervisor (team), Admin |
| `GET /reports/{name}?…&format=csv` | Supervisor, Admin |

### 14.3 Integration endpoints (role `Integration`)

`POST /integration/billing/acks`, `POST /integration/master-data` (optional),
`GET /integration/maintenance-referrals?since=`.

### 14.4 API conventions

- Versioning by URL; breaking changes require `/api/v2` and a `MinAppVersion` bump.
- Paging: `page`, `pageSize` (max 200); response `{items, page, pageSize, total}`.
- Rate limiting per user and per device (§15).
- Health: `/health/live`, `/health/ready` (unauthenticated, no data).

---

## 15. Security requirements

| ID | Requirement |
|---|---|
| SEC-001 | All traffic TLS 1.2+; HSTS on portal; no HTTP endpoints. |
| SEC-002 | API validates Entra access tokens (issuer, audience, signature, lifetime) and enforces role + data scope on every endpoint (FR-ROLE-02). Authorisation is implemented as policies and tested per endpoint (§18). |
| SEC-003 | Service-to-service and app-to-Azure access uses managed identities (SQL, Blob, Key Vault). No connection-string passwords or storage account keys in config. |
| SEC-004 | Secrets and certificates in Azure Key Vault; nothing secret in source control, app binaries or logs. |
| SEC-005 | Blob container private, no anonymous access; images served only through the API after scope check (FR-009.4). |
| SEC-006 | Data at rest encrypted: Azure SQL TDE, Blob SSE; device DB with SQLCipher, images in app-private encrypted files, keys in Android Keystore. |
| SEC-007 | Mobile app meets OWASP MASVS L1 plus resilience controls (root/integrity, no debug in release, obfuscation with R8, `allowBackup=false`, screenshots blocked on capture screens via `FLAG_SECURE`). |
| SEC-008 | Input validation on all API inputs; parameterised queries only (EF Core); file uploads checked for type by magic bytes, size and dimensions. Images are stored exactly as received (re-encoding would break the hash) and never processed or rendered server-side. |
| SEC-009 | Rate limits: 60 submissions/min per device, 600 requests/min per user; 429 with `Retry-After`. |
| SEC-010 | Play Integrity verdicts verified server-side with Google's API; failing devices are blocked when `RequirePlayIntegrity` is true. |
| SEC-011 | Logs and telemetry shall not contain tokens, images, or personal data beyond user ID and device ID. |
| SEC-012 | Audit log (§13 `AuditLog`) records: sign-in to portal, every reading submission and rejection, exception decisions, corrections, reassignments, master-data/LOV/setting changes, device register/revoke/purge, period open/close, billing re-sends. Retained `AuditRetentionYears` (default 7) `[ASSUMPTION]`. |
| SEC-013 | Portal: anti-forgery tokens, secure/HttpOnly/SameSite cookies, CSP, session timeout 30 min idle. |
| SEC-014 | Dependency and container scanning in CI; no high/critical findings at release. Penetration test before production go-live. |
| SEC-015 | Personal data handling follows the organisation's data protection policy and applicable law; cloud resources in the approved Azure region `[OPEN — OD-08]`. |

---

## 16. Non-functional requirements

Volume figures are `[ASSUMPTION — OD-03]` design targets until the business supplies real numbers.

| ID | Requirement |
|---|---|
| NFR-001 | Capacity: 150,000 meters, 150 concurrent readers, 20,000 submissions/day, peak 30 submissions/second. |
| NFR-002 | API p95 latency < 500 ms for reads, < 2 s for a submission excluding upload transfer time. |
| NFR-003 | A submission with 2 images shall complete in < 10 s on a 3G-class connection (1 Mbps up). |
| NFR-004 | App cold start < 3 s; meter screen opens < 1 s from cached data; works fully offline (§9). |
| NFR-005 | Availability 99.5% monthly for API and portal; planned maintenance outside 06:00–18:00 local time. |
| NFR-006 | RPO ≤ 15 min, RTO ≤ 4 h. Azure SQL point-in-time restore ≥ 35 days; Blob soft delete 30 days. |
| NFR-007 | Image retention `ImageRetentionYears` (default 7) via Blob lifecycle (cool tier after 90 days, archive after 1 year). Storage estimate: 150k × 2 × 400 KB ≈ 120 GB/month. |
| NFR-008 | Android 10+ (API 29+), phones 5.5"–6.8", must be usable outdoors in bright sun (high-contrast theme, large touch targets ≥ 48 dp) and with gloves. |
| NFR-009 | The reader app is **English only** (decided 2026-10-04), in short everyday words, with a read-aloud button on every screen for readers who find reading hard. Readings always use Western digits. |
| NFR-015 | Branding: Dubai Investments Park colours — navy `#12305C` for the main action, taupe `#776759` for the brand and sewerage. Light theme only for outdoor use. |
| NFR-010 | Accessibility: WCAG 2.1 AA for the portal. |
| NFR-011 | Observability: OpenTelemetry traces/metrics/logs to Application Insights with correlation ID from app to DB; dashboards for submission rate, rejection/exception rates, outbox backlog, sync lag; alerts on outbox failures, 5xx > 1%, queue age. |
| NFR-012 | Mobile crash and performance reporting without personal data (tool `[OPEN — OD-10]`). |
| NFR-013 | Environments: Dev, Test/UAT, Production, isolated (separate Entra app registrations, databases, storage). Test uses `FakeBillingAdapter` unless integration testing. |
| NFR-014 | Maintainability: backend unit test coverage ≥ 80% for domain rules; all business rules in the domain layer as pure, testable functions. |

---

## 17. Delivery phases

The v1.0 draft placed the supervisor portal in Phase 6, but exceptions hold billing, so a minimal
exception workflow must exist before billing goes live. Phases are revised accordingly.

| Phase | Content | Exit criteria |
|---|---|---|
| 1 — Foundation | Repos, CI/CD, IaC, Entra app registrations and roles, DB schema + seed, master-data CSV import (API), device registration, `/me`, `/config`, `/sync/assignments`, app sign-in and navigation (§4.1–4.6) | Reader signs in and browses assigned meters offline in Test |
| 2 — Manual capture & validation | Reading screen, camera, quality checks, all meter statuses, offline outbox, `POST /readings`, full server validation (§6–7), idempotency, audit | All Appendix B vectors pass; field pilot with 5 readers, no billing |
| 3 — OCR assist | ML Kit OCR with crop, prefill rules, OCR flags and sampling, accuracy report; start collecting labelled pairs | OCR accuracy measured on pilot data; thresholds calibrated |
| 4 — Supervisor workflow (minimal portal) | Exception queue/decisions, reassignment, progress, period open/close, revisit tasks, average calculation, escalation rules | Supervisors clear a full pilot period end to end |
| 5 — Billing integration & go-live | Outbox, billing adapter, acks, reconciliation, corrections with reversals, security test, performance test, production rollout | Parallel run of one period reconciles 100% with billing; pen test closed |
| 6 — Full portal & AI | Admin master-data UI, LOV/settings/status-rule editors, device management, all reports, maintenance referrals, optional cloud OCR, custom vision model | — |

---

## 18. Testing and acceptance

| ID | Requirement |
|---|---|
| TST-001 | Every BR and every validation step in §7.2 has unit tests; Appendix B vectors are implemented as a data-driven test suite. |
| TST-002 | API integration tests run against a real SQL Server (Testcontainers) and Azurite for Blob, covering each endpoint's happy path, validation errors and **authorisation matrix** (§3.2) — one test per role × capability. |
| TST-003 | Idempotency tests: duplicate submission, duplicate with different payload, retry after timeout, concurrent duplicates. |
| TST-004 | Android: unit tests for view models and local validation; instrumented tests for the outbox (airplane mode, process death mid-send, reboot), camera flow on at least 3 device models. |
| TST-005 | Offline scenario test: 300 captures offline, reconnect, all delivered in order with correct outcomes. |
| TST-006 | Performance test at NFR-001 load for 1 hour; NFR-002 targets met. |
| TST-007 | Security: SAST/dependency scan in CI, MASVS checklist, external pen test (Phase 5). |
| TST-008 | UAT scripts per role covering §4–§12; sign-off by business owner and billing owner. |

---

## 19. Implementation guide for Claude Code

This section is written for an AI coding agent (and humans) implementing the system.

### 19.1 Repository layout (proposed)

```
/CLAUDE.md                      agent working notes (see Appendix D)
/docs/spec.md                   this document — the source of truth
/docs/decisions/                ADRs, one per resolved open decision
/server/
  MeterReading.sln
  src/MeterReading.Domain/        entities, value objects, business rules (pure, no I/O)
  src/MeterReading.Application/   use cases, validation pipeline, interfaces (IBillingAdapter, IImageStore, IClock)
  src/MeterReading.Infrastructure/ EF Core DbContext + migrations, Blob store, billing adapters, Entra/Play Integrity clients
  src/MeterReading.Api/           ASP.NET Core endpoints, auth policies, OpenAPI
  src/MeterReading.Worker/        outbox dispatcher, period/average/revisit/escalation jobs
  src/MeterReading.Portal/        Blazor Web App
  tests/MeterReading.Domain.Tests/   incl. Appendix B vectors
  tests/MeterReading.Api.Tests/      integration + authorisation matrix
/android/
  app/                            navigation, DI
  core/{auth,network,database,sync,camera,ocr,ui}/
  feature/{dashboard,zones,properties,meters,capture,myreadings}/
/infra/                           Bicep
/.github/workflows/               build, test, scan, deploy
```

### 19.2 Rules for implementation

1. **Spec IDs in code and tests.** Reference requirement IDs in test names and in comments on the code that implements a non-obvious rule (e.g. `// BR-006 rollover`). Do not invent behaviour not in this spec; if something is missing, add it to §20 and ask.
2. **Business rules live in `Domain`** as pure functions: consumption calculation, rollover, average, expected range, status rules, exception/flag evaluation. No database or HTTP access there; inject `IClock` for time.
3. **The server decides.** App-side validation is a convenience mirror of server rules, driven by `/config`; never trust it.
4. **Configuration, not constants.** Every threshold in §11.3 is read from `Setting`; every status rule from `StatusRule`; every reason from `LovItem`.
5. **Idempotency first.** Implement §7.3 before any other part of `POST /readings`.
6. **Transactions.** Status change + outbox insert + audit insert happen in one DB transaction. Image blobs are uploaded before the DB commit; orphaned blobs (no committed row after 24 h) are cleaned by the worker.
7. **Never edit readings in place**; corrections create new rows (§8.5).
8. **Authorisation tested per endpoint** with the §3.2 matrix (TST-002).
9. **Schema by EF Core migrations**, named for what they do; reviewed SQL script generated per release (`dotnet ef migrations script --idempotent`) for DBAs.
10. **Build in phase order** (§17), one vertical slice per pull request, each with tests passing in CI.

### 19.3 Definition of done (per requirement)

Code + tests referencing the ID; OpenAPI updated; audit events emitted where SEC-012 requires;
settings/LOV seeded; no new warnings; CI green; README or ADR updated if a decision was made.

---

## 20. Open decisions

Each has a proposed default that development can use now. Resolve each with an ADR in `/docs/decisions/`.

| ID | Decision | Proposed default | Owner |
|---|---|---|---|
| OD-01 | Source of master data (zones, properties, meters): this system or billing? | Admin-maintained with CSV import; billing feed optional later (INT-005) | Business + Billing |
| OD-02 | Billing interface: transport, format, sync/async ack, sandbox availability | REST push with async ack (INT-007), adapter pattern | Billing owner |
| OD-03 | Real volumes: meters, readers, periods per year, images per reading | NFR-001 figures | Business |
| OD-04 | Final meter status LOV and per-status rules (§6.1) | As §6.1 | Business |
| OD-05 | Average method and default for meters with no history (BR-007) | Mean of last 3 actual; per-type default | Business + Billing |
| OD-06 | Expected range thresholds (BR-008) | Factor 3.0, floor 100 m³ | Business |
| OD-07 | Catch-up after estimated periods (BR-011): who performs true-up | Billing; we send actual since last actual + estimated list | Billing owner |
| OD-08 | Azure region / data residency, incl. cloud OCR | Approved local region; cloud OCR off | Information security |
| OD-09 | Languages | **Resolved:** reader app English only | Business |
| OD-10 | Mobile crash reporting tool | Tool without personal data, e.g. Sentry self-hosted or App Insights | IT architecture |
| OD-11 | Meter numbering convention and uniqueness scope | Free text, unique among active meters system-wide | Business |
| OD-12 | Reading period cadence and windows; single open period | Monthly, one open period | Business |
| OD-17 | Work assignment | **Resolved:** no assignment; shared meter lists, narrowed by zone | Business |
| OD-13 | Shared devices policy (FR-020.8) | One active user; admin purge | Business + Security |
| OD-14 | Retention: images and audit | 7 years each | Legal / Records |
| OD-15 | Who may create a new meter in the field (BR-005) | Created on supervisor approval of the replacement | Business |
| OD-16 | Technology baseline (§2.3), incl. .NET version and portal framework | As §2.3 | IT architecture |

---

## Appendix A — Error codes

| Code | HTTP | Meaning | App behaviour |
|---|---|---|---|
| VALIDATION_FAILED | 422 | Field-level errors in `errors` | Show fields; re-capture |
| MANDATORY_FIELD_MISSING | 422 | Status rule not met | Re-capture |
| INVALID_LOV_CODE | 422 | Code unknown/inactive | Refresh config; re-capture |
| READING_EXCEEDS_REGISTER | 422 | Too many digits | Re-capture |
| STATUS_NOT_ALLOWED | 422 | Status invalid for this meter | Re-capture |
| METER_NUMBER_IN_USE | 422 | Replacement new meter number already active | Re-capture |
| IMAGE_HASH_MISMATCH | 422 | Hash differs from uploaded bytes | Retry once from stored file, then re-capture |
| IMAGE_INVALID | 422 | Type/size/dimension invalid | Re-capture |
| CAPTURE_TIME_INVALID | 422 | Clock skew or too old | Show "contact supervisor" |
| PERIOD_CLOSED | 422 | Period not open | Show "contact supervisor" |
| METER_NOT_ASSIGNED | 403 | Not assigned to caller at capture time | Remove locally; inform user |
| ALREADY_READ | 409 | Another reading already accepted | Inform user |
| TRANSACTION_ID_REUSED | 409 | Same ID, different payload | Log as defect; re-capture with new ID |
| DEVICE_REVOKED | 403 | Device revoked | Wipe per FR-002.3 |
| DEVICE_NOT_REGISTERED | 403 | Unknown device | Re-register |
| INTEGRITY_FAILED | 403 | Play Integrity failed | Block |
| APP_VERSION_UNSUPPORTED | 426 | Update required | Prompt update |
| RATE_LIMITED | 429 | Too many requests | Back off per `Retry-After` |
| READER_NOT_FOUND | 403 | Signed-in user is not an active reader in vw_MR_Reader | Show "ask your supervisor" |
| LOGIN_NOT_UNIQUE | 409 | Two active readers share the sign-in name in vw_MR_Reader | Show "ask your supervisor" |
| NO_OPEN_PERIOD | 409 | No reading period is open | Show "ask your supervisor" |
| METER_NOT_FOUND | 404 | No active meter with this id (barcode) | Refresh meter list |
| READING_NOT_FOUND | 404 | Photo sent for a reading that is not the caller's or not stored | Send the reading first |
| IMAGE_TOO_LARGE | 413 | Photo above the size limit | Shrink and retry |
| IMAGE_ID_REUSED | 409 | Same image id, different photo | Log as defect |
| TOO_MANY_IMAGES | 422 | More photos than allowed per reading | Drop the extra photo |
| NO_TENANT | 422 | The property has no current tenant in vw_MR_Tenant (FR-006.12) | Show "tell your supervisor"; nothing saved |
| TENANT_NOT_CONFIRMED | 422 | The reading came without the tenant the reader checked | Ask the reader to tap the tenant |
| TENANT_CHANGED | 409 | The checked tenant is no longer a tenant of the property | Refresh; meter back to "read again" |

## Appendix B — Business rule test vectors

Register 5 digits (max 99,999), `AveragePeriods` 3 with history averaging 300, `HighConsumptionFactor` 3.0, floor 100 (ExpectedHigh = 900), proximity 10% unless stated.

| # | Status / input | Expected consumption | Basis | Outcome |
|---|---|---|---|---|
| B1 | WORKING prev 50,000 → new 52,500 (avg 1,000, ExpectedHigh 3,000) | 2,500 | ACTUAL | ACCEPTED |
| B2 | WORKING prev 50,000 → new 50,800 | 800 | ACTUAL | ACCEPTED |
| B3 | WORKING prev 50,000 → new 51,000 | 1,000 | ACTUAL | EXCEPTION HIGH_CONSUMPTION |
| B4 | WORKING prev 50,000 → new 49,900 | — | — | EXCEPTION LOWER_THAN_PREVIOUS (prev not within 10% of max) |
| B5 | WORKING prev 99,950 → new 120 | 170 | ROLLOVER | ACCEPTED + flag ROLLOVER |
| B6 | WORKING prev 99,950 → new 9,000 | 9,050 | ROLLOVER | EXCEPTION ROLLOVER_OUT_OF_RANGE |
| B7 | WORKING prev 50,000 → new 50,000 | 0 | ACTUAL | ACCEPTED + flag ZERO_CONSUMPTION |
| B8 | WORKING, 3rd consecutive zero | 0 | ACTUAL | EXCEPTION REPEATED_ZERO |
| B9 | WORKING new 123,456 on 5-digit register | — | — | REJECT READING_EXCEEDS_REGISTER |
| B10 | FIRST_READING opening 0 → new 35 | 35 | ACTUAL | ACCEPTED |
| B11 | FIRST_READING opening 12 → new 35 | 23 | ACTUAL | ACCEPTED |
| B12 | FIRST_READING on a meter with history | — | — | REJECT STATUS_NOT_ALLOWED |
| B13 | DAMAGED, no reading | 300 (on approve with AVERAGE) | AVERAGE | EXCEPTION DAMAGED_METER; MaintenanceReferral created |
| B14 | DAMAGED, reading 50,250 from prev 50,000, approved ACTUAL | 250 | ACTUAL | EXCEPTION → APPROVED |
| B15 | SUBMERSED, 1st period | 300 | AVERAGE | ACCEPTED |
| B16 | SUBMERSED, 2nd consecutive period | 300 | AVERAGE | EXCEPTION REPEATED_SUBMERSION |
| B17 | METER_REPLACED old prev 10,000 final 10,400; new opening 5 current 60 | 400 + 55 = 455 | SPLIT (2 lines) | EXCEPTION METER_REPLACEMENT; on approve old deactivated, new created |
| B18 | NOT_ACCESSIBLE, not revisited by close | 300 at close | AVERAGE | Revisit task; at close posted + flag ESTIMATED_NO_ACCESS |
| B19 | REMOVED final 10,500 prev 10,400 | 100 | ACTUAL | EXCEPTION METER_REMOVAL; meter deactivated on approve |
| B20 | REMOVED, no final reading | 300 | AVERAGE | EXCEPTION METER_REMOVAL |
| B21 | DAMAGED, no history, no default configured | — | — | EXCEPTION NO_AVERAGE_BASIS |
| B22 | Same transactionId, same payload, sent twice | — | — | 2nd returns 200 with identical body |
| B23 | Same transactionId, different payload | — | — | 409 TRANSACTION_ID_REUSED |
| B24 | Captured while assigned, reassigned before delivery | per rules | — | Accepted (capture-time assignment check) |
| B25 | OCR 52,500 @ 0.98, confirmed 52,800 | 2,800 | ACTUAL | ACCEPTED (if in range) + flag OCR_MISMATCH |
| B26 | OCR 52,500 @ 0.80 | — | — | App does not prefill (BR-OCR-03) |

## Appendix C — Changes from v1.0

1. Added missing §2 (system overview, technology baseline) and §7–§20, appendices A–D.
2. Defined SEC, NFR and INT requirements that v1.0 named but did not contain.
3. Replaced "real time / immediately" with offline-first capture and defined sync (§9); added offline grace (FR-001.6) to reconcile idle timeout with field work.
4. Unified three conflicting status lists into device, reading and assignment state models (§7.1); defined rejection vs exception vs flag (§7.2).
5. Fixed FR-ROLE-03 (scope follows the authorising role) and permission-matrix inconsistencies (admin "own assignments", retry rights, photo caveat, correction process).
6. Assignment unit defined as meter-per-period (§7.4); zones/properties derived from it.
7. Renamed NEW_METER to FIRST_READING and used the meter's opening reading instead of a fixed 0; defined who creates the new meter in a replacement.
8. Added image roles; raised default max images to 4 so replacements fit; defined hash over final bytes; metadata sent as JSON rather than EXIF.
9. Defined average, expected range, rollover candidate test, catch-up, revisit and escalation rules with configurable thresholds.
10. Moved minimal supervisor workflow to Phase 4, before billing go-live (Phase 5).
11. Cleaned the §4.5 hierarchy (property code is an attribute) and example data.

## Appendix D — Starter `CLAUDE.md` for the project repository

```markdown
# Meter Reading System — working notes for Claude

Sewerage & irrigation meter reading: Android app (Kotlin) + ASP.NET Core API + Blazor portal +
worker, on Azure with Entra ID. The specification is `docs/spec.md` — it is the source of truth.
Read the relevant section before changing behaviour, and cite requirement IDs (FR-, BR-, SEC-,
NFR-, INT-) in test names and on non-obvious code.

- Business rules are pure functions in `server/src/MeterReading.Domain`. Appendix B of the spec
  is a data-driven test suite there; keep it passing.
- The server decides. App validation mirrors server rules from `/config`; never the other way.
- Thresholds, status rules and reason codes are configuration (Setting, StatusRule, LovItem),
  never constants.
- Readings are never edited in place; corrections create new rows.
- Status change + outbox + audit are one DB transaction.
- Schema changes only via EF Core migrations, named for what they do.
- Every endpoint has authorisation tests per the role matrix (spec §3.2).
- If the spec is silent or contradictory, do not guess: add an item to spec §20 and ask.

Commands: `dotnet build server/MeterReading.sln`, `dotnet test server/MeterReading.sln`,
`./android/gradlew -p android test lint`.
```
