param([switch]$Full)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$settingsPath = Join-Path (Split-Path $projectRoot -Parent) 'work-notes\maven-settings.xml'
docker version --format '{{.Server.Version}}'
if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop Linux engine must be running.' }
$mavenArgs = @('-s', $settingsPath, '-f', (Join-Path $projectRoot 'pom.xml'))
if (-not $Full) {
    $mavenArgs += '-Dtest=RedisReservationStoreTest,ReservationCompensationIntegrationTest,ReservationTimeoutCompensationServiceTest,RedisOrphanRecoveryJobTest,ReconciliationServiceTest,ActivityCacheServiceTest'
}
$mavenArgs += 'verify'
& mvn @mavenArgs
if ($LASTEXITCODE -ne 0) { throw 'FlashGuard verification failed. Inspect target/surefire-reports.' }
Write-Host 'PASS: selected tests and packaging succeeded. This is not RocketMQ or capacity acceptance.'
