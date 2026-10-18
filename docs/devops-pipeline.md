# Azure DevOps pipeline: deploy mApps and zApps

`azure-pipelines.yml` (repository root) builds and tests the API and the gateway on every push to `main`,
then waits for an approval and deploys to UAT: **mApps** (IIS on UATWEB01) first, then **zApps**
(Azure App Service `zapps`). Each deploy is followed by a health check; mApps is rolled back by itself
if the new version does not answer.

| Stage | Where it runs | What it does |
|---|---|---|
| Build | Microsoft-hosted Windows | Publishes both self-contained (win-x64), runs the gateway tests |
| Test | Microsoft-hosted Ubuntu | SQL Server in a container with `db/` scripts, then the API and gateway tests |
| UAT → mApps | Agent on UATWEB01 | Backup, app_offline, stop pool, copy files (**web.config and logs kept**), start, `/health/ready`, rollback if it fails |
| UAT → zApps | Agent in the VNet | Zip deploy to `zapps` (with `deploy/zApps.web.config`), then `/gateway/health` |

Database scripts are **not** run by the pipeline: before approving, a DBA runs any new `db/0xx_*.sql`
in MRDB (in number order; all are re-runnable).

## One-time setup

### 1. Repository
Repos → Branches → `main` → ⋯ → **Set as default branch**.

### 2. Environment "UAT" with UATWEB01 (for mApps)
1. Pipelines → **Environments** → **New environment** → name `UAT` → Resource **Virtual machines** → Next.
2. Provider **Generic**, OS **Windows** → copy the registration script.
3. On **UATWEB01**, PowerShell **as administrator**, paste and run it. When asked for tags, enter `mapps`.
   The agent runs as a Windows service; it needs to stop and start the app pool and write
   `C:\Websites\mApps`, so leave it as Local System or use an administrator service account.
4. UATWEB01 needs outbound HTTPS to `dev.azure.com`, `*.dev.azure.com`, `*.vsblob.visualstudio.com`
   and `vstsagentpackage.azureedge.net`. With Zscaler, ask IT to exclude these from SSL inspection.
5. **Approvals:** Environment `UAT` → ⋯ → **Approvals and checks** → **Approvals** → add the approvers.

`C:\Websites\mApps\web.config` must already be there, filled in (from `deploy/mApps.web.config`). The
pipeline never deploys or changes it.

### 3. Agent for zApps (inside the VNet)
Until this agent is online, runs deploy **mApps only**: the zApps job runs only when *Deploy zApps too* is
ticked on **Run pipeline** (runs started by a push leave it off). UATWEB01 cannot be used: zapps' SCM site
answers it 403 (public access off) and `zApps.dipark.com` is not reachable from it.
`zapps` has public access off, so the deploy must come from a machine that reaches its private endpoint
(and its SCM site, `zAppsScmHost`, through the privatelink DNS).
1. Project settings → **Agent pools** → **Add pool** → Self-hosted → name `DIP-VNet`.
2. Pool → **New agent** → Windows → follow the steps on a VM in `ApplicationGateway1VN` (or any server
   that resolves and reaches zapps' private endpoint). Run it as a service.
3. That machine needs outbound HTTPS to `dev.azure.com` and the zapps SCM site. Check from it:
   `Test-NetConnection zapps-g4c5d5arhxehajau.scm.uaenorth-01.azurewebsites.net -Port 443` should
   show a private (10.x) address and `TcpTestSucceeded : True`.

### 4. zApps deploy credentials (publish profile)
No Azure service connection is used (creating one needs Owner or User Access Administrator on the
subscription). The pipeline zip-deploys to the zapps SCM site with the publish profile's credentials.
1. Azure portal → App Service `zapps` → **Configuration** → **General settings** → *SCM Basic Auth
   Publishing Credentials* **On** → Save.
2. `zapps` → **Overview** → **Download publish profile**. Open the file in Notepad and find the
   `ZipDeploy` (or `MSDeploy`) profile: `publishUrl`, `userName` (starts with `$`), `userPWD`.
3. Pipelines → `DIPFieldServiceApps` → **Edit** → **Variables** → add `zAppsDeployUser` = `userName` and
   `zAppsDeployPassword` = `userPWD` with **Keep this value secret** ticked → Save. Delete the downloaded
   file afterwards; never commit it or paste it anywhere.
4. Check `zAppsScmHost` in `azure-pipelines.yml` equals `publishUrl` without `:443`.
5. If the password ever leaks, `zapps` → Overview → **Reset publish profile**, then update the variable.

### 5. The pipeline
1. Pipelines → **New pipeline** → **Azure Repos Git** → `DIPFieldServiceApps` → **Existing Azure
   Pipelines YAML file** → `/azure-pipelines.yml`.
2. Set the variables at the top of the file (or in Pipelines → Library → a variable group of the same names):

| Variable | Value |
|---|---|
| `zAppsName` | `zapps` |
| `zAppsAgentPool` | `DIP-VNet` |
| `zAppsScmHost` | `publishUrl` from the publish profile, without `:443` |
| `zAppsDeployUser` / `zAppsDeployPassword` | Pipeline variables (password secret), step 4 |
| `mAppsSitePath` / `mAppsAppPool` | `C:\Websites\mApps` / the IIS app pool's name |
| `mAppsHealthUrl` / `zAppsHealthUrl` | `https://mApps.dipark.com/health/ready` / `https://zApps.dipark.com/gateway/health` |

3. **Run**. The first run asks to allow the environment and the pool.

## Each release
1. Push to `main` (or merge a pull request). Build and Test run.
2. A DBA runs any new `db/0xx_*.sql` in MRDB.
3. The approver opens the run → **Review** → **Approve**. mApps, then zApps, are deployed and checked.
4. If mApps fails its check, the previous version is back and the run is red: see Event Viewer →
   Application on UATWEB01. Backups (last five) are in `C:\Websites\_backup\`.

## Not in this pipeline
The phone app (APK) and the end-to-end emulator tests run on GitHub Actions (`.github/workflows/`), which
does not run from Azure Repos.
