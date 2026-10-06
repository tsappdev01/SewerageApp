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
        │ https 443, gateway's client certificate (mutual TLS)
 ┌──────▼─────────── internal ───┐
 │  MeterReading.Api (IIS)       │   accepts only the gateway's certificate
 └──────┬────────────────────────┘
        │ 1433
     UATWEB01 / MRDB
```

## What it does

| Protection | Detail |
|---|---|
| Fixed list of requests | Only the app's calls, each with its methods (`Routes.cs`): register, me, sync/meters, properties/search, readings/mine, summary, meters/{id}, send a reading, photo GET/PUT, the four Field Inspection calls, and `/health/live` for the app's **Test** button. Anything else: `404 NOT_FOUND`, or `405 METHOD_NOT_ALLOWED` on a known path. The list is code, not configuration, so a settings slip cannot open more of the API. `/health/ready` is **not** passed on: it names tables and views. |
| Header cleaning | Drops `X-Dev-User` (test sign-in), `Forwarded`, `X-Original-For`; sets its own `X-Forwarded-For`/`-Proto` so the API sees the phone's address. Removes `Server` and `X-Powered-By` from answers. |
| Size limits | JSON 64 KB, an inspection visit 512 KB, photos 2.1 MB: larger is `413 REQUEST_TOO_LARGE` before it reaches the API. |
| Rate limits per address | 600 requests a minute overall; 10 phone registrations a minute (`429 RATE_LIMITED`). |
| Mutual TLS to the API | Shows its client certificate; can pin the API's certificate by thumbprint. Refuses to start without a client certificate when the API address is https (outside Development). |
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

| Certificate | Where | Purpose |
|---|---|---|
| **Public** (from a public CA) for `zApps.dipark.com` | Gateway server, IIS binding | Phones trust it. Self-signed will not work on phones. |
| **API server** certificate (internal CA, or self-signed + pinned) | API server, IIS binding | The gateway's https connection to the API. |
| **Gateway client** certificate (internal CA, or self-signed) | Gateway server, `LocalMachine\My` | Proves to the API it is the gateway. |

Self-signed gateway client certificate (on the gateway server, PowerShell as administrator):
```powershell
$c = New-SelfSignedCertificate -Subject "CN=meterreading-gateway" -CertStoreLocation Cert:\LocalMachine\My `
     -KeyExportPolicy NonExportable -KeySpec Signature -KeyLength 2048 -NotAfter (Get-Date).AddYears(2) `
     -TextExtension @("2.5.29.37={text}1.3.6.1.5.5.7.3.2")    # Client Authentication
$c.Thumbprint                                              # note it: used on both servers
Export-Certificate -Cert $c -FilePath C:\Temp\meterreading-gateway.cer   # public part only
```
Give the app pool read access to its private key: `certlm.msc` → Personal → Certificates → the
certificate → *All Tasks → Manage Private Keys* → add `IIS AppPool\MeterReadingGateway`, Read.

On the **API server**, IIS must trust it: import `meterreading-gateway.cer` into *Trusted Root
Certification Authorities* (`certlm.msc`) — or skip this by issuing the certificate from the
internal CA the API server already trusts.

### 2. Gateway server (DMZ)

1. Install the **ASP.NET Core 10.0 Hosting Bundle**, `iisreset`.
2. Copy `publish-gateway` to e.g. `D:\Sites\MeterReadingGateway`. App pool `MeterReadingGateway`,
   *No Managed Code*. Site with an **https binding on 443** and the public certificate; no http.
3. Configuration Editor → `system.webServer/aspNetCore` → `environmentVariables`:

| Name | Value |
|---|---|
| `ASPNETCORE_ENVIRONMENT` | `Production` |
| `Gateway__ApiBaseUrl` | the internal API: `https://mApps.dipark.com/` (also the default in `appsettings.json`) |
| `Gateway__ClientCertificate__Thumbprint` | the gateway client certificate's thumbprint (or `__Path` + `__Password` for a `.pfx`) |
| `Gateway__ApiCertificateThumbprint` | *optional*: the API server certificate's thumbprint. Use it when that certificate is self-signed or from a CA the DMZ server does not trust. Update it when the certificate is renewed. |

Optional: `Gateway__RequestsPerMinute`, `Gateway__RegistrationsPerMinute`, `Gateway__TimeoutSeconds`.

4. **Check:** `https://<public address>/gateway/health` → `{"status":"live"}` (the gateway alone);
   `https://<public address>/health/live` → `{"status":"live"}` (through to the API);
   `https://<public address>/health/ready` → `404` (kept internal).

### 3. Internal API server

Add to the API site's environment variables (in addition to `docs/deploy-steps.md` B6):

| Name | Value |
|---|---|
| `Gateway__KnownProxies__0` | the gateway server's IP as the API sees it. Only then is its `X-Forwarded-For` used, so rate limits count per phone; from any other address it is ignored. |
| `Gateway__RequireClientCertificate` | `true` |
| `Gateway__ClientCertificateThumbprints__0` | the gateway client certificate's thumbprint (add `__1` for the next one before renewing) |

And in IIS Manager → the API site → **SSL Settings**: *Require SSL* ticked, *Client
certificates* = **Require**.

**Check** (from the gateway server): `/api/v1/me` through the gateway → `403
DEVICE_NOT_REGISTERED` (it reached the API). From an office PC straight to the API →
refused at TLS by IIS. With *Require*, IIS asks every caller for the certificate, `/health` too.
If internal monitoring must call `/health/ready` on the API directly, set *Client certificates* to
**Accept** instead: the API then refuses everything except `/health/*` without the gateway's
certificate (`403 GATEWAY_REQUIRED`).

### 4. Firewall

| From | To | Port | |
|---|---|---|---|
| Internet | Gateway | 443 | allow |
| Gateway | API server | 443 | allow (only this) |
| API server | UATWEB01 | 1433 | allow |
| DMZ | SQL Server | any | **block** |
| Internet | API server | any | **block** |

If a load balancer or WAF sits in front of the gateway and hides the phones' addresses, all phones
share one address for the rate limits; raise `Gateway__RequestsPerMinute` or let the WAF limit.

### 5. Phones

The server address in the app's **Settings** (and `-PapiBaseUrl` in the build) is the gateway's
public address, `https://zApps.dipark.com/` (the default in release builds and in the GitHub Actions APK). Nothing else changes on the
phone.

## Renewing certificates

- **Gateway client certificate:** create the new one, add its thumbprint as
  `Gateway__ClientCertificateThumbprints__1` on the API, recycle; then switch the gateway's
  `Gateway__ClientCertificate__Thumbprint`, recycle; then remove the old thumbprint.
- **API certificate with pinning:** set `Gateway__ApiCertificateThumbprint` to the new thumbprint
  at the moment the API's binding switches.
