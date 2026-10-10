# Security tests of the public API (gateway → API → SQL Server)

How each item of the go-live security checklist is verified, and its result. The automated part runs in
the **End to end** workflow (`.github/workflows/e2e.yml`) against a throw-away copy of the whole system
built from this repository: SQL Server with `db/`, the API as in production (Device sign-in), and the
DMZ gateway in front. Results of every run go to the `e2e-results` branch (`security-results.md`,
`zap-report.html`, `dependencies.txt`, `gitleaks.txt`, API and gateway test output).

**What this does not replace:** an independent penetration test of the deployed UAT/production
system, and the checks of DIP's own servers and network (section 12). Those need DIP's sign-off.

Results below are from run 10 (commit d00214f, 2026-10-10): every check green.

## 1. Authentication and authorization

| Check | How | Result |
|---|---|---|
| Every protected endpoint refuses a request without a phone key | security-tests 1.1, 12 endpoints | Pass: 403 `DEVICE_NOT_REGISTERED` |
| Wrong key, unknown phone, another phone's key, malformed id | 1.2–1.5 | Pass |
| Blocked phone refused at once | 1.6, app test `RevokedPhoneTest` | Pass: 403 `DEVICE_REVOKED`; the app does not open |
| Development header `X-Dev-User` cannot sign in through the gateway | 1.7 | Pass: stripped |
| Reader not in the reader list | 1.8 | Pass: 403 `READER_NOT_FOUND` |
| Registration code works once; only hashes of codes and keys stored | 1.9, 1.10, API tests | Pass |
| **A phone cannot act as another reader** (object-level authorization) | 2.4, 2.7, API tests `FR002_7_*` | Pass, **after fix**: was a finding (any registered phone could name any reader); now a phone belongs to its first reader (FR-002.7, db/013) |
| Another reader cannot download or add photos to a reader's reading (IDOR) | 2.1, 2.2 | Pass: 404 |
| "My readings" holds only the reader's own | 2.3, API tests | Pass |
| Server-controlled fields: the phone a reading came from | 2.5, 2.6 | Pass, **after fix**: was taken from the body; now from the key |
| Roles enforced on the server | API: every endpoint requires the reader policy | Pass |
| No administrative endpoints public | 4.1 | Pass: none exist in the API; the gateway passes only the app's 14 routes |

Note: refusals answer **403** with a reason code (spec Appendix A), not 401. The request is refused either way;
the phone acts on the code.

## 2. Input validation and injection

| Check | How | Result |
|---|---|---|
| SQL injection in search, meter id, zone filter | 3.1–3.4, ZAP active scan (SQL injection rules) | Pass: all SQL is parameterised (Dapper); search runs in memory; tables intact |
| Malformed JSON, wrong types, unknown fields (mass assignment, e.g. `"status":"APPROVED"`) | 3.5–3.7 | Pass: 400 `VALIDATION_FAILED`, no internals |
| Unsupported content type | 3.8 | Pass: 415 |
| Business rules on the server (codes, digits, tenant on site, capture time) | 3.10–3.13 | Pass |
| Path traversal | 4.1, 5.6, ZAP | Pass |
| Errors without SQL, stack traces, paths or connection details | 7.1, ZAP "Application Error Disclosure" | Pass |

## 3. Abuse and rate limiting

| Check | How | Result |
|---|---|---|
| Per-phone rate limit, also with a forged `X-Forwarded-For` | 5.8 | Pass: 116 × 200, 44 × 429 in a burst of 160 |
| Registration code guessing | 5.9 | Pass: refused with 429 within the first tries of a minute (20 tries: 5 × 422, 15 × 429) |
| Oversized JSON, deeply nested JSON, oversized photo | 5.1–5.3 | Pass: 413 / 400 / 413 |
| Photos per reading capped (storage exhaustion) | 5.7 | Pass |

## 4–6. Network, HTTP methods and headers

| Check | How | Result |
|---|---|---|
| Internal endpoints not reachable from outside (`/health/ready`, OpenAPI, Swagger, config files) | 4.1 | Pass: 404 |
| Unsupported methods, TRACE, method-override header | 4.2–4.4 | Pass: 405 |
| Security headers; server not named | 4.5, ZAP passive scan | Pass: HSTS, nosniff, X-Frame-Options, CSP, Referrer-Policy, CORP, no-store |
| CORS | Not enabled: the API serves the phone app only | n/a |
| HTTPS, certificate, TLS versions; SQL Server not public; NSG/WAF | DIP, section 12 | **DIP to verify** |

## 6. Replay and duplicates (billing)

| Check | How | Result |
|---|---|---|
| Same reading sent twice stores it once | 6.1, app test (retries), db check 8 | Pass |
| Same id with a changed number cannot overwrite | 6.2 | Pass: 409 |
| A read meter cannot be read again, by anyone | 6.3, 6.4 | Pass: 409 `ALREADY_READ` |
| No duplicate rows in `MaintainMeterReading` | 6.5, db check 9 | Pass |

## 7. Files

| Check | How | Result |
|---|---|---|
| Content checked (JPEG signature), not the name | 5.4 | Pass |
| SHA-256 checked on arrival; never overwritten | 5.5, db checks 5, 7, 12 | Pass |
| Only the reader's own photos can be downloaded | 2.1 | Pass |
| Stored in the database (or private folder/blob), never public | design (`IImageStore`) | Pass; for Azure Blob, DIP to confirm the container is private |
| Malware scanning | Not done: only JPEGs are accepted, never executed or served to others | DIP to decide |

## 8. Logging

| Check | How | Result |
|---|---|---|
| Refused sign-ins audited with a reason | 7.3 | Pass |
| Rate-limit refusals logged | 7.4 | Pass |
| No keys or passwords in logs | 7.2 | Pass |
| Alerts reach the support team | DIP | **DIP to set up** |

## 9. Dependencies, secrets, scans

| Check | How | Result |
|---|---|---|
| Known-vulnerable .NET packages (including transitive) | `dotnet list package --vulnerable` | Pass: none |
| Secrets in the repository history | gitleaks over all commits of the branch | Pass: none |
| Dynamic scan (OWASP ZAP API scan, passive and active, signed in) | `zap-scan.sh` | Pass: 0 failures, 0 warnings (119 rules passed) |
| Production does not run in Development mode | the API refuses Development sign-in outside Development | Pass |
| Independent penetration test | — | **DIP to commission** |

## 10. Functional end to end (the app)

The real Android app on an emulator, through the gateway: registration, a reading with photo and tenant,
a reading without signal sent later, an inspection with GPS and photo, My readings, Summary, and a blocked
phone: 6 of 6 pass. The database afterwards: 13 of 13 checks pass (`db/dev/900_e2e_checks.sql`). API test
suite 157 of 157, gateway 43 of 43.

## 11. Findings and fixes

| Finding | Severity | Status |
|---|---|---|
| A registered phone could act as any active reader (`X-Reader` not bound to the phone) | High | Fixed: FR-002.7, db/013, tests 2.4, 2.7 |
| Readings and visits stored without the phone that sent them (DeviceId from the body) | Medium | Fixed: taken from the key; tests 2.5, 2.6 |
| `db/010` failed under `sqlcmd` (QUOTED_IDENTIFIER) | Low (deployment) | Fixed |
| Cross-Origin-Resource-Policy header missing (ZAP) | Low | Fixed |

## 12. For DIP to verify and sign off

1. HTTPS only on zApps, with a trusted certificate; TLS 1.2+; HTTP closed or redirected.
2. From the internet only the gateway is reachable; the API, SQL Server and UATWEB01 are not (NSG, firewall, WAF).
3. The gateway reaches the API with its client certificate (`Gateway__RequireClientCertificate=true`).
4. Production settings: `ASPNETCORE_ENVIRONMENT=Production`, `Auth__Mode=Device`; secrets (the `mr_api`
   password, rotated) in Key Vault or the server's protected settings, not in files.
5. Run `db/013_device_reader_binding.sql` before this API version.
6. Monitoring: alerts on repeated refusals, 429s and 5xx from the gateway and API logs.
7. An independent penetration test of UAT, with critical/high findings fixed and retested, then sign-off by
   the accountable security owner.
