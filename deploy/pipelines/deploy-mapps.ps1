<#
  Deploys the published API to the IIS site mApps (run by the Azure DevOps agent on UATWEB01; see
  docs/devops-pipeline.md). Keeps the server's own web.config (it holds the database password) and logs.
  Takes a backup first; if the API does not answer HealthUrl afterwards, puts the backup back.
#>
param(
    [Parameter(Mandatory)] [string] $Source,
    [Parameter(Mandatory)] [string] $SitePath,
    [Parameter(Mandatory)] [string] $AppPool,
    [Parameter(Mandatory)] [string] $HealthUrl,
    [int] $HealthTries = 18
)
$ErrorActionPreference = 'Stop'
Import-Module WebAdministration

if (-not (Test-Path (Join-Path $SitePath 'web.config'))) {
    throw "$SitePath\web.config is missing. Put the filled-in deploy/mApps.web.config there once (docs/devops-pipeline.md)."
}

function Copy-Site([string] $from, [string] $to) {
    # /MIR mirrors; web.config, logs and app_offline.htm are never touched.
    robocopy $from $to /MIR /XF web.config app_offline.htm /XD logs /NFL /NDL /NJH /NP /R:3 /W:5 | Out-Host
    if ($LASTEXITCODE -ge 8) { throw "robocopy failed ($LASTEXITCODE)" }
    $global:LASTEXITCODE = 0
}

function Wait-Healthy {
    foreach ($i in 1..$HealthTries) {
        try {
            $r = Invoke-WebRequest -Uri $HealthUrl -UseBasicParsing -TimeoutSec 20
            if ($r.StatusCode -eq 200) { return $true }
        } catch { }
        Start-Sleep -Seconds 10
    }
    return $false
}

$backup = Join-Path (Split-Path $SitePath -Parent) ("_backup\" + (Split-Path $SitePath -Leaf) + "-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
Write-Host "Backup of the running site: $backup"
New-Item -ItemType Directory -Force -Path $backup | Out-Null
robocopy $SitePath $backup /MIR /XD logs /NFL /NDL /NJH /NP | Out-Null

Write-Host "Stopping app pool $AppPool"
Set-Content -Path (Join-Path $SitePath 'app_offline.htm') -Value '<html><body>Updating, back in a minute.</body></html>'
if ((Get-WebAppPoolState -Name $AppPool).Value -ne 'Stopped') { Stop-WebAppPool -Name $AppPool }
foreach ($i in 1..30) { if ((Get-WebAppPoolState -Name $AppPool).Value -eq 'Stopped') { break }; Start-Sleep 1 }

# Keep the API running with its meter list in memory (SourceCache): an idle or recycled pool loses it,
# and the next phone waits 30-50 s for PMS's views and gives up (seen 2026-10-18).
Set-ItemProperty "IIS:\AppPools\$AppPool" -Name processModel.idleTimeout -Value ([TimeSpan]::Zero)
Set-ItemProperty "IIS:\AppPools\$AppPool" -Name startMode -Value AlwaysRunning
Set-ItemProperty "IIS:\AppPools\$AppPool" -Name recycling.periodicRestart.time -Value ([TimeSpan]::Zero)
Get-Website | Where-Object { $_.physicalPath -eq $SitePath } | ForEach-Object {
    Set-ItemProperty "IIS:\Sites\$($_.Name)" -Name applicationDefaults.preloadEnabled -Value $true
}

try {
    Copy-Site $Source $SitePath
} finally {
    Remove-Item (Join-Path $SitePath 'app_offline.htm') -ErrorAction SilentlyContinue
    Start-WebAppPool -Name $AppPool
}

if (Wait-Healthy) {
    Write-Host "mApps is up: $HealthUrl"
    # Keep the last five backups.
    Get-ChildItem (Split-Path $backup -Parent) -Directory | Where-Object Name -like ((Split-Path $SitePath -Leaf) + '-*') |
        Sort-Object Name -Descending | Select-Object -Skip 5 | Remove-Item -Recurse -Force
    exit 0
}

Write-Warning "mApps did not answer $HealthUrl; putting the previous version back."
Stop-WebAppPool -Name $AppPool
foreach ($i in 1..30) { if ((Get-WebAppPoolState -Name $AppPool).Value -eq 'Stopped') { break }; Start-Sleep 1 }
Copy-Site $backup $SitePath
Start-WebAppPool -Name $AppPool
throw "Deployment failed its health check and was rolled back. See Event Viewer → Application on UATWEB01."
