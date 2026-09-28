param([switch]$Full, [string]$MavenSettings)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$settingsPath = Join-Path (Split-Path $projectRoot -Parent) 'work-notes\maven-settings.xml'
if ($MavenSettings) { $settingsPath = (Resolve-Path -LiteralPath $MavenSettings -ErrorAction Stop).Path }
docker version --format '{{.Server.Version}}'
if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop Linux engine must be running.' }
$mavenArgs = @('-Dflashguard.test.db.url=')
if (Test-Path -LiteralPath $settingsPath -PathType Leaf) { $mavenArgs += @('-s', $settingsPath) }
if (-not $Full) {
    $mavenArgs += '-Dtest=RedisReservationStoreTest,ReservationCompensationIntegrationTest,ReservationTimeoutCompensationServiceTest,RedisOrphanRecoveryJobTest,ReconciliationServiceTest,ActivityCacheServiceTest'
}
$mavenArgs += 'verify'
Push-Location $projectRoot
try {
    & mvn @mavenArgs
    if ($LASTEXITCODE -ne 0) { throw 'FlashGuard verification failed. Inspect target/surefire-reports.' }
} finally { Pop-Location }
Write-Host 'PASS: selected tests and packaging succeeded. This is not RocketMQ or capacity acceptance.'
