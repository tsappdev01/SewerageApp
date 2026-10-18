# DMZ gateway

The phones reach the system over the internet, but the DMZ has no route to the database. The
gateway is the only thing in the DMZ: it listens on **443**, passes the Meter Reader app's own
requests on to the internal Meter Reading API, and answers everything else itself. It has **no
database, no connection string and stores nothing**.

```
 Phones (mobile data)
        │ https 443
 ┌──────▼──────────────── DMZ ───┐
 │  MeterReading.Gateway (IIS)   │   public certificate; no database access
 └──────┬────────────────────────┘
        │ https 443 (the API's server certificate)
 ┌──────▼─────────── internal ───┐
 │  MeterReading.Api (IIS)       │   reachable only from the gateway's IP (firewall)
 └──────┬────────────────────────┘
        │ 1433
     UATWEB01 / MRDB
```

## What it does

| Protection | Detail |
|---|---|
| Fixed list of requests | Only the app's calls, each with its methods (`Routes.cs`): register, me, sync/meters, properties/search, finalize a property, readings/mine, summary, meters/{id}, send a reading, photo GET/PUT, the four Field Inspection calls, and `/health/live` for the app's **Test** button. Anything else: `404 NOT_FOUND`, or `405 METHOD_NOT_ALLOWED` on a known path. The list is code, not configuration, so a settings slip cannot open more of the API. `/health/ready` is **not** passed on: it names tables and views. |
| Header cleaning | Drops `X-Dev-User` (test sign-in), `Forwarded`, `X-Original-For`; sets its own `X-Forwarded-For`/`-Proto` so the API sees the phone's address. Removes `Server` and `X-Powered-By` from answers. |
| Size limits | JSON 64 KB, an inspection visit 512 KB, photos 2.1 MB: larger is `413 REQUEST_TOO_LARGE` before it reaches the API. |
| Rate limits | Per phone (its `X-Device-Id`): 120 requests a minute, and photo uploads apart, 120 a minute. Per internet address 3,000 a minute (phones on one mobile network share an address), 6,000 a minute for everyone together, 10 phone registrations a minute per address. Over a limit: `429 RATE_LIMITED`. |
| Security headers | Every answer: `Strict-Transport-Security`, `X-Content-Type-Options: nosniff`, `X-Frame-Options`, `Content-Security-Policy`, `Referrer-Policy`, `Cross-Origin-Resource-Policy`, `Cache-Control: no-store`. |
| Audit log | One line per request under the log category `Audit`: route, status, time, phone id, address, trace id, and a reason code when refused (`NOT_FOUND`, `METHOD_NOT_ALLOWED`, `REQUEST_TOO_LARGE`, `RATE_LIMITED`, `API_UNAVAILABLE`, or `API_REFUSED` when the API said no; the API logs why). Never the device key, bodies or query strings. |
| https to the API | Always https outside Development (it refuses to start with a plain-http API address, except an API on the same machine); can pin the API's certificate by thumbprint. **No client certificate** (DIP decision 2026-10-12): phones are authenticated by their registration, device key and reader (FR-002), which the API checks on every call. A client certificate is still supported if wanted later (`Gateway__ClientCertificate__*`). |
| API down | `502`/`504 API_UNAVAILABLE`; the phone keeps the reading in its encrypted queue and sends later. |

The API still checks everything else as before: the phone's key, the reader, the tenant and the
business rules. The gateway adds no trust of its own.

## Build and test

```bash
dotnet test gateway/MeterReading.Gateway.slnx      # no database needed
dotnet publish gateway/src/MeterReading.Gateway -c Release -o publish-gateway
```

## Deploy

### 1. Certificates

Only server certificates, for https. No client certificates: phones prove themselves by their
registration (one-time code → device key, checked by the API on every call).

| Certificate | Where | Purpose |
|---|---|---|
| **Public** (from a public CA) for `zApps.dipark.com` | Gateway server, IIS binding | Phones trust it. Self-signed will not work on phones. |
| **API server** certificate for `mApps.dipark.com` (internal CA, or self-signed + pinned) | API server, IIS binding | Encrypts the gateway's connection to the API. |

If the API certificate is self-signed or from a CA the DMZ server does not trust, either import it
into the gateway server's *Trusted Root Certification Authorities*, or pin it with
`Gateway__ApiCertificateThumbprint` (below).

### 2. Gateway server (DMZ)

1. Install the **ASP.NET Core 10.0 Hosting Bundle**, `iisreset`.
2. Copy `publish-gateway` (with `deploy/zApps.web.config` as its `web.config`) to e.g.
   `D:\Sites\MeterReadingGateway`. App pool `MeterReadingGateway`, *No Managed Code*. Site with an
   **https binding on 443** and the public certificate; no http.
3. Configuration Editor → `system.webServer/aspNetCore` → `environmentVariables`:

| Name | Value |
|---|---|
| `ASPNETCORE_ENVIRONMENT` | `Production` |
| `Gateway__ApiBaseUrl` | the internal API, **https**: `https://mApps.dipark.com/` (also the default in `appsettings.json`) |
| `Gateway__ApiCertificateThumbprint` | *optional*: the API server certificate's thumbprint, when that certificate is self-signed or from a CA the DMZ server does not trust. Update it when the certificate is renewed. |

Optional: `Gateway__RequestsPerMinutePerPhone`, `Gateway__UploadsPerMinutePerPhone`, `Gateway__RequestsPerMinute`
(per address), `Gateway__TotalRequestsPerMinute`, `Gateway__RegistrationsPerMinute`, `Gateway__TimeoutSeconds`.

To keep the audit lines in a file for the SIEM, turn on `stdoutLogEnabled="true"` in `web.config`
(they go to `logs\stdout_*.log`) or point the SIEM agent at the Windows event log; set
`Logging__LogLevel__Audit` to `Warning` to keep only refusals.

4. **Check:** `https://<public address>/gateway/health` → `{"status":"live"}` (the gateway alone);
   `https://<public address>/health/live` → `{"status":"live"}` (through to the API);
   `https://<public address>/health/ready` → `404` (kept internal);
   `https://<public address>/api/v1/me` → `403 DEVICE_NOT_REGISTERED` (the API refuses an unregistered phone).

### 2a. On Azure App Service instead (DIP: app `zapps`, resource group DotNetSites)

- **Configuration → General settings:** Platform **64 Bit** (the publish is win-x64; 32 Bit gives
  *HTTP Error 500.32 ANCM Failed to Load dll*), HTTPS only **On**, minimum TLS **1.2**, FTP state **FTPS
  only**, Always on **On**, Session affinity **Off**. The *.NET version* there does not matter: the
  publish is self-contained (.NET 10).
- Deploy: upload the whole `publish` folder to `/site/wwwroot` with `deploy/zApps.web.config` as
  `web.config` (stop the app first; on errors, empty `wwwroot` and upload again).
- At DIP the app is private (public network access off, private endpoint): phones reach it only once the
  Application Gateway in ApplicationGateway1VN publishes `zapps.dipark.com` (listener on 443 with the
  *.dipark.com certificate, health probe `/gateway/health`, WAF body limit at least 4 MB) and public DNS
  points `zapps.dipark.com` at the Application Gateway's public IP.

### 3. Internal API server

Add to the API site's environment variables (in addition to `docs/deploy-steps.md` B6):

| Name | Value |
|---|---|
| `Gateway__KnownProxies__0` | the gateway server's IP as the API sees it. Only then is its `X-Forwarded-For` used, so rate limits count per phone; from any other address it is ignored. |

Leave `Gateway__RequireClientCertificate` unset (false), and in IIS → the API site → **SSL
Settings**: *Require SSL* ticked, *Client certificates* = **Ignore**.

Because there is no client certificate, the **firewall** is what keeps everyone but the gateway away
from the API: allow 443 to the API server **only from the gateway's IP** (section 4). The API still
refuses any caller without a registered phone's key.

### 4. Firewall

| From | To | Port | |
|---|---|---|---|
| Internet | Gateway | 443 | allow |
| Gateway | API server | 443 | allow (only this; and the API server accepts 443 only from the gateway's IP) |
| API server | UATWEB01 | 1433 | allow |
| DMZ | SQL Server | any | **block** |
| Internet | API server | any | **block** |

If a load balancer or WAF sits in front of the gateway and hides the phones' addresses, all phones
share one address; the per-phone limits still apply, so raise `Gateway__RequestsPerMinute` (per address) or let the WAF limit.

### 5. Phones

The server address in the app's **Settings** (and `-PapiBaseUrl` in the build) is the gateway's
public address, `https://zApps.dipark.com/` (the default in release builds and in the GitHub Actions APK). Nothing else changes on the
phone.

## Renewing certificates

- **Public certificate (zApps):** bind the new one in IIS on the gateway server before the old expires.
- **API certificate:** bind the new one on the API server; if pinned, set `Gateway__ApiCertificateThumbprint`
  to the new thumbprint at the moment the API's binding switches.
