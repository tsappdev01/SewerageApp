# Deployment: our servers

| Part | Server | Address | Site / folder | Certificate |
|---|---|---|---|---|
| Internal API | UATWEB01 (192.168.28.13), with MRDB | `https://mApps.dipark.com/` (443, SNI; internal DNS only) | IIS site `mApps`, pool `mApps`, `C:\Websites\mApps` | `*.dipark.com` wildcard, `01F82BC4F71E28299159021CEDC37FBF8073574B`, expires **30 Jan 2027** |
| DMZ gateway | DMZ server | `https://zApps.dipark.com/` (443, public DNS) | IIS site `zApps` | public certificate for `zApps.dipark.com`; client key `CN=zApps-gateway` |
| Phones | | server address `https://zApps.dipark.com/` | | |

- `mApps.web.config`, `zApps.web.config`: the sites' `web.config` with placeholders. Fill in on the server only.
- Both are published **self-contained** (`Properties/PublishProfiles/FolderProfile.pubxml`), so the servers need no
  .NET 10 install and IIS starts the `.exe`. A `processPath="dotnet"` in web.config fails with 500.30
  ("hostpolicy.dll ... not found in C:\Program Files\dotnet"). Republish after each monthly .NET 10 patch.
- When copying a new build, keep the site's `web.config` (the publish brings a plain one).
- The mApps binding asks for the gateway's certificate during the TLS handshake. IIS Manager does not set this,
  so after any change to the binding check, and if needed redo:
  ```powershell
  netsh http show sslcert hostnameport=mApps.dipark.com:443     # Negotiate Client Certificate : Enabled
  netsh http delete sslcert hostnameport=mApps.dipark.com:443
  netsh http add sslcert hostnameport=mApps.dipark.com:443 certhash=<wildcard thumbprint> appid="{4dc3e181-e14b-4a21-b022-59fc669b0914}" certstorename=MY clientcertnegotiation=enable
  ```
- Firewall: Internet → zApps 443; zApps → 192.168.28.13 443 only; nothing from the DMZ to SQL Server.
- Wildcard renewal: redo the `netsh` lines above with the new thumbprint. Nothing changes on the gateway.
