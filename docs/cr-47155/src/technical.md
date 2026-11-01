## 1. Purpose and scope

This document describes the Sewerage & Irrigation Meter Reading System (DIP Field Service) as built and deployed on 1 November 2026: its parts, how they talk to each other, the data it reads and writes, the billing rules, the invoice and report it produces, security, deployment, operations and testing. It is the reference for IT, support and auditors. Business rules are numbered as in `docs/spec.md` (FR-, BR-, SEC-, NFR-).

| Item | Value |
| --- | --- |
| System | DIP Field Service: phone app, zApps gateway, mApps API, PropertyManagementSystem database |
| Code | GitHub `tsappdev01/SewerageApp` (branch `main`), mirrored to Azure Repos `TechSourcedxb/DIPFieldServiceApps` |
| Version | App 1.0.0 (build 99); API and gateway at commit `834170b` |
| Database | SQL Server on UATWEB01, database PropertyManagementSystem, schema `mr` |
| Related | CR #47155 BRD, FRD, Architecture, Deployment Guide, Release, UAT |

## 2. Architecture

!DIAGRAM

The phone talks only to the gateway. The gateway passes a fixed list of routes to the API and stores nothing. The API holds the business rules and is the only part that touches the database. PMS's own data is read through views and is never changed, except the two writes listed in section 6.

| Part | Technology | Hosted on | Role |
| --- | --- | --- | --- |
| Phone app "DIP Field Service" | Kotlin 2.4.21, Compose Multiplatform 1.11.1, Ktor 3.5.2; Android 10+ (API 29–36) and iOS (Xcode 26) | Readers' phones | Meter list, reading capture with photos, offline queue, finalize, field inspection |
| zApps gateway | .NET 10, YARP reverse proxy | Azure App Service | The only part reachable from the internet; fixed route list, rate limits, security headers, audit log |
| mApps API | .NET 10 minimal API, Dapper, Microsoft.Data.SqlClient 7.1 | IIS on UATWEB01 | Phone registration, readings, photos, finalize, invoices, report |
| Database | SQL Server, PropertyManagementSystem | UATWEB01 | Schema `mr` (the app's tables and views) beside PMS's `dbo` |
| CI | GitHub Actions | GitHub | APK build, end-to-end test on an emulator, security scans, screenshots, iOS build |
| Deployment | Azure DevOps pipeline (`azure-pipelines.yml`) | Azure Repos | Builds and deploys mApps and zApps from `main` |

## 3. Phone app

### 3.1 Structure

One Kotlin Multiplatform code base: `android/app/src/commonMain` holds screens, rules, offline queue and server calls; `androidMain` and `iosMain` hold only camera, key store, lock, location, speech and notifications. `ios/` is the Xcode shell. App ID `ae.dipark.fieldservice`.

### 3.2 Screens

| Screen | What it does |
| --- | --- |
| Start | Registers the phone by its ANDROID_ID on first start (IT approves it in SQL); then opens the reader's list |
| Home | Next meter to read, progress, download, search, My readings, My summary, Field Inspection |
| Zones, Properties, Meters | Browse by zone and route; a property's meters with Finalize property at the bottom |
| How is the meter? | SEWERAGE METER / IRRIGATION METER banner; PMS meter statuses (OK, Faulty, Damaged, Covered with water, Meter changed); Next; Skip property, go to the next |
| Photo, Number, Check | Photo of the dial, reading typed as a whole number (no commas), warnings for unusual use, tenant check, send |
| Sent / Saved | Sent to the server, or kept on the phone until there is signal; moves to the next meter within 1 second |
| My readings, My summary | The reader's readings this month; counts; Upload now; Finalize Meter Reading for the Month (supervisor PIN) |
| Field Inspection | Plan, property, location check, units with result, check and send |

### 3.3 Behaviour worth knowing

- **Offline first:** readings and photos queue on the phone and are sent when there is signal; a reading is stored first and its photos follow one by one, each checked against its SHA-256.
- **Read again:** a meter can be read more than once a month; the new reading replaces the earlier one, which is kept as SUPERSEDED. Refused once the reading was copied to PMS (ALREADY_BILLED).
- **Several meters at one property:** two meters show as Sewerage / Irrigation tabs; more than two as a list with "Change meter".
- **Skip property:** opens the next property in route order (zone, then route, round to the start); nothing is recorded.
- **Messages** at the bottom close with ✕ or a tap; a "please wait" window covers the screen while finalizing.
- **Security on the phone:** device key in the Android Keystore / iOS Keychain; screens with readings block screenshots; app lock; cleartext HTTP not allowed.

## 4. Gateway (zApps)

- Fixed routes only; anything else is 404:

| Route | Method | Purpose |
| --- | --- | --- |
| `/api/v1/devices/enrol`, `/devices/register`, `/devices/status` | POST, POST, GET | Phone registration and status |
| `/api/v1/me` | GET | The reader and the open period |
| `/api/v1/sync/meters` | GET | Meter list for the reader's zones |
| `/api/v1/properties/search` | GET | Search |
| `/api/v1/meters/{meterId}` | GET | One meter |
| `/api/v1/readings` | POST | Send a reading |
| `/api/v1/readings/{transactionId}/images/{imageId}` | GET, PUT | Photos |
| `/api/v1/readings/mine`, `/api/v1/summary` | GET | My readings, My summary |
| `/api/v1/properties/{propertyCode}/finalize` | POST | Finalize property (FR-037) |
| `/api/v1/periods/current/close` | POST | Finalize Meter Reading for the Month (FR-037.13/17) |
| `/api/v1/inspections/plan`, `/units`, `/inspections`, `/{visitId}/images/{imageId}` | GET, GET, POST, GET/PUT | Field Inspection |
| `/health/live` | GET | Health |

- Strips `X-Dev-User` and forwarding headers and sets its own `X-Forwarded-For`; the API trusts it only from `Gateway:KnownProxies`.
- Per-phone rate limits, security headers, an audit line per request with a reason code.
- Gateway to API is HTTPS only; the firewall lets only the gateway reach the API.

## 5. API (mApps)

### 5.1 Configuration

Set as environment variables in the server's `web.config` (never deployed from the repository; template `deploy/mApps.web.config`):

| Setting | Value on UAT |
| --- | --- |
| `ConnectionStrings__MeterReading` | Server=UATWEB01; Database=PropertyManagementSystem; User=mr_api (password on the server only) |
| `SourceViews__Schema` | `mr` (the API reads `mr.vw_MR_*`) |
| `Auth__Mode` | `Device` (registered phone key + reader) |
| `PmsTransfer__Enabled` | Copy of readings into PMS `MaintainMeterReading` on or off |
| `Gateway__KnownProxies__0` | The gateway's address |
| `ImageStore:Kind` | `Database` (default, table `mr.ReadingImageData`); Folder or Azure Blob possible |

### 5.2 Main services

| Service | Responsibility |
| --- | --- |
| `ReaderService` | Meter list per reader and zone, state of each meter (to read, sent, checking, read again, revisit) |
| `SubmitService` | Validates and stores a reading; tenant check (FR-006.12); replaces an earlier reading (SUPERSEDED) |
| `FinalizeService` | Pairs meters, prices them, writes the billing rows and the invoice; Finalize month |
| `MeterReadingRepository` | All SQL (Dapper); every view column CAST to the row type |
| `PmsTransferService` | Optional copy of readings into PMS `MaintainMeterReading` |
| `InspectionService` | Field Inspection rules and storage |

Errors are problem details with a `code` from spec Appendix A, e.g. `PROPERTY_NOT_COMPLETE`, `PERIOD_CLOSED`, `ALREADY_BILLED`, `ALREADY_INVOICED`, `DEVICE_REVOKED`.

## 6. Data

### 6.1 What is read (never written)

PMS's views, through pass-through views in schema `mr` (db/028): `vw_MR_Reader`, `vw_MR_Zone`, `vw_MR_Property` (with TranCode, Area, Rate, BaanDimensionCode), `vw_MR_Tenant` (with BaanCode), `vw_MR_ReadingPeriod`, `vw_MR_MeterStatus`, `vw_MR_InspectionPlan`, `vw_MR_InspectionUnit`; and `mr.vw_MR_Meter` built on PMS `MaintainPropertyMeter` with the opening reading.

**Opening reading** of a meter: the last finalized month before the open one (`mr.MaintainFinalizedMeter`), else PMS's history loaded once into `mr.MeterOpeningReading` (`mr.usp_FillMeterOpeningReading`), else 0.

### 6.2 The app's own tables (schema mr)

| Table | Holds |
| --- | --- |
| `mr.Device`, `mr.DeviceRegistrationCode` | Registered phones: key hash only, ANDROID_ID, reader binding, revoke time |
| `mr.ReadingTransaction` | Every reading: meter, reader, phone, status, value, consumption, tenant, GPS, times |
| `mr.ReadingImage`, `mr.ReadingImageData` | Photo index and bytes |
| `mr.MaintainFinalizedMeter` | Billing detail: one row per meter number per finalize (sewerage and irrigation side by side), amounts, VAT, codes, `InvNo` |
| `mr.ReadingPeriodStatus` | A month closed by the app |
| `mr.IrrigationNoCharge` | Plots whose irrigation is not charged after a date |
| `mr.PropertySubTenant` | Sub-tenant name per property, pre-filled on the phone |
| `mr.MeterOpeningReading` | Opening readings loaded from PMS history |
| `mr.InvoiceReportSetting`, `mr.InvoiceReportLog` | Report recipients and the audit of every report sent |
| `mr.Inspection*` | Field Inspection visits, unit results, photos |

### 6.3 Writes outside schema mr

| Table (PMS) | Written by | Rule |
| --- | --- | --- |
| `dbo.MaintainMeterReading` | `PmsTransferService`, when `PmsTransfer:Enabled` | Insert only, once per reading, in one transaction with the `PmsRowId` update |
| `dbo.MaintainFinalizedInvoices` | Finalize (API and `db/ops/finalize_month.sql`) | Insert with `InvNo` from PMS's IDENTITY via `OUTPUT`; update only while the invoice is untouched; never delete except the app's own untouched invoices |

## 7. Finalizing and billing (FR-037)

### 7.1 When a property can be finalized

Every meter of the property has a reading this month on the server (sent, or "can't reach"). The month must be open.

### 7.2 Amounts

| Rule | Formula |
| --- | --- |
| Amount (each meter) | consumption × FloatValue × 1.2 × RateFactor / 100, rounded to 2 decimals |
| 10% of sewerage | 10% of the sewerage amount on the same billing row (same meter number) |
| Irrigation billed | the calculated amount if above 10% of sewerage or above 500; else 10% of sewerage, at most 500. No sewerage on the row: as calculated |
| No-charge plot | irrigation 0 after the date in `mr.IrrigationNoCharge` |
| VAT | 5% of each amount (VATCode SSD) |
| Total | sewerage + irrigation + both VATs |

Sewerage and irrigation meters with the same number share a row (FR-037.3). TenantCode, BaanCode and company come fresh from `vw_MR_Tenant`, TranCode from `vw_MR_Property`; Area, Rate and DimensionCode from `vw_MR_Property`; sub-tenant from the reading, else `mr.PropertySubTenant`.

### 7.3 Invoice header and detail (FR-037.16)

In one transaction:

1. Insert the invoice into `dbo.MaintainFinalizedInvoices` without InvNo; read the new InvNo back with `OUTPUT inserted.InvNo`. PMS's IDENTITY and its unique index `UX_MaintainFinalizedInvoices_InvNo` make a duplicate impossible.
2. Insert the meter rows into `mr.MaintainFinalizedMeter` with that InvNo (foreign key `FK_MaintainFinalizedMeter_Invoice`).
3. Set `SIMNo` = InvNo and `SourceRowId` = 900000000000 + the first meter row's RowId (kept once set).

| Invoice field | Value |
| --- | --- |
| TranCode, TenantsName, PropertyCode, BaanCode, DimensionCode | From the views as above |
| Area, Rate | From `vw_MR_Property` (text, as PMS keeps them) |
| FromDate, ToDate | The period's StartDate and EndDate |
| TaxableAmount | Total excluding VAT |
| VATAmount | Total VAT |
| InvAmount | Total including VAT |
| RecAmount | 0 |
| TTBaan, Status | 'No', 'Ready' |
| Details | 'Irr' when the property used no sewerage and only irrigation, else 'sew' |
| VATCode | 'SSD' |
| UserName | The reader (or `office:` + login from SSMS) |
| CreatedDate | PMS's default |

Finalizing again in the month keeps the earlier rows with IsCurrent = 0 and updates the same invoice while it is untouched (TTBaan 'No', Status 'Ready', nothing posted to Fusion, nothing received). Once PMS has taken it up, the phone is told ALREADY_INVOICED and nothing changes.

### 7.4 Finalize Meter Reading for the Month (FR-037.13, .17)

Behind the supervisor PIN on My summary. The phone sends what waits on it first; then the server finalizes every fully read property not yet finalized, each with its invoice, closes the month for every reader (`mr.ReadingPeriodStatus`), and answers with how many properties were finalized, how many invoices were made and how many were left with meters to read. The office can do the same from SSMS with `db/ops/finalize_month.sql` (preview first).

### 7.5 Invoice Generation Report (FR-037.18)

`mr.usp_SendInvoiceReport` runs when the month is finalized. It checks every invoice (exists in PMS; InvAmount = its meter rows; Taxable + VAT = InvAmount; SIMNo and SourceRowId set) and the month (one invoice per finalized property; totals agree), lists failed invoices and pending properties, builds an HTML email (summary, status, every invoice) and sends it with Database Mail (`msdb.dbo.sp_send_dbmail`, profile `noreply`) to the recipients in `mr.InvoiceReportSetting`. Each report is logged in `mr.InvoiceReportLog` (counts, amounts, validation, mail item, dispatch status and time). A report that cannot be sent never undoes finalizing.

## 8. Security

| Area | Control |
| --- | --- |
| Phone identity | Registered by ANDROID_ID, approved by IT in SQL; the phone gets a device key; only the key's hash is stored; a phone belongs to the first reader who signs in on it |
| Blocking | IT revokes a phone (`db/ops/revoke_device.sql`); its next call is refused (DEVICE_REVOKED); readings on it are kept |
| Transport | HTTPS phone to gateway and gateway to API; no client certificate (DIP decision); firewall allows only the gateway to the API |
| Database | Login `mr_api` with rights on schema `mr` only, plus INSERT and column-level SELECT / UPDATE on `dbo.MaintainFinalizedInvoices`, EXECUTE on the report procedure and msdb DatabaseMailUserRole. No DELETE on PMS tables |
| Input | Unknown JSON fields refused; sizes and formats checked; photos checked against SHA-256 |
| Audit | Gateway and API audit lines with reason codes; readings keep phone, reader, GPS and times; superseded readings kept |
| Testing | Security tests through the gateway, OWASP ZAP scan, known-vulnerable package check and secret scan on every change |

## 9. Database scripts

All SQL is in `db/`, numbered, re-runnable. Run in PropertyManagementSystem in number order; `db/dev/` never outside development.

| Script | Purpose |
| --- | --- |
| 002–025 | The app's tables and columns (readings, photos, phones, inspection, finalize, amounts, codes, period close, VAT) |
| 026 | `mr.MeterOpeningReading` and its fill procedure |
| 028 | `mr.vw_MR_*` views over PMS, `mr.vw_MR_Meter` with the opening reading, grants |
| 030 | Sub-tenant per property |
| 031 | Opening reading from the last finalized month first |
| 032 | `mr.MaintainFinalizedMeter` with PMS's column names, Area, Rate, DimensionCode, TTBaan, Status, FusionInvoiceNo, InvNo |
| 033 | Invoice header in `dbo.MaintainFinalizedInvoices`, foreign key, grants |
| 034 | Invoice Generation Report: settings, log, procedure, Database Mail rights |
| 035 | `mr_api` may update Details ('Irr' / 'sew') |

| Office script (`db/ops/`) | Use |
| --- | --- |
| `approve_device.sql`, `list_devices.sql`, `revoke_device.sql`, `rebind_device.sql`, `new_device_code.sql` | Phones |
| `finalize_month.sql` | Finalize the month from SSMS (preview, one property, earlier month, close) |
| `close_reading_period.sql`, `reopen_reading_period.sql` | Close or reopen a month |
| `delete_all_readings.sql` | Delete readings, billing rows and the app's untouched invoices, with backup |
| `set_sub_tenant.sql` | Sub-tenant names |
| `fill_meter_opening_reading.sql` | Reload opening readings from PMS history |
| `test_amounts.sql` | Work out sewerage and irrigation amounts for typed readings, with the irrigation rule applied |
| `set_invoice_report_recipients.sql`, `send_invoice_report.sql`, `invoice_report_status.sql` | Report recipients, send again, delivery status |

## 10. Deployment

1. Push `main` to Azure Repos (`git push https://dev.azure.com/TechSourcedxb/_git/DIPFieldServiceApps main`).
2. Run any new `db/` scripts in PropertyManagementSystem (in number order), before the API that needs them.
3. The Azure DevOps pipeline builds and deploys mApps (UATWEB01) and zApps (Azure App Service). The server's `web.config` is never overwritten.
4. Check `/health/live` through the gateway.
5. Phones: install the APK from the GitHub release `android-v1.0.0-buildNN` (Google Play `ae.dipark.fieldservice` for production).

## 11. Monthly operations

| When | Who | What |
| --- | --- | --- |
| Start of month | IT / office | Open the new period in PMS (`vw_MR_ReadingPeriod`); opening readings come from the last finalized month |
| During the month | Readers | Read meters; Finalize property as each is done |
| End of month | Supervisor | Finalize Meter Reading for the Month (PIN); invoices made; month closed |
| After finalizing | Finance | Receive the Invoice Generation Report by email; check failed / pending |
| Any time | IT | Approve or block phones; `invoice_report_status.sql` for mail delivery |

## 12. Testing and quality

| Check | Where | What |
| --- | --- | --- |
| API tests | `api/tests` (xUnit, real SQL Server) | Rules, amounts, finalize, invoice, report, readers, phones |
| Gateway tests | `gateway/tests` | Route list, header stripping, limits |
| Phone unit tests | `android/app/src/androidUnitTest` | Rules, repository, formatting |
| End to end | GitHub Actions "End to end" | SQL Server + API + gateway + emulator: register, read with photo, offline, inspection, blocked phone, DB checks |
| Security | Same run | Security tests, OWASP ZAP, vulnerable packages, gitleaks |
| Screenshots | "Android screenshots", "iOS app" | Every screen from the current code |

A change goes to `main` only when the End to end run is green.

## 13. Support and troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| "0 properties" | Views or zones: check `mr.vw_MR_Meter` and the reader's zones in `vw_MR_Reader` |
| Phone waits for approval | IT runs `db/ops/approve_device.sql` with the phone's ID and code |
| "This phone has been blocked" | Revoked by IT; rebind or approve again |
| Finalize refused: meters left | Read or "can't reach" the remaining meters |
| ALREADY_INVOICED | The invoice is in use in PMS; the office corrects it in PMS |
| Report not received | `db/ops/invoice_report_status.sql`; check the `noreply` profile and `msdb.dbo.sysmail_event_log` |
| Month closed by mistake | `db/ops/reopen_reading_period.sql` |
