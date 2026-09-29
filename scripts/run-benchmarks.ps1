[CmdletBinding()]
param(
    [ValidateSet('Preflight','Full','Sql','Cluster')][string]$Mode = 'Full',
    [ValidateRange(1,10000)][int]$Rate = 10,
    [ValidateRange(1,5000)][int]$Vus = 200,
    [string]$MavenSettings
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$settings = Join-Path (Split-Path $projectRoot -Parent) 'work-notes\maven-settings.xml'
if ($MavenSettings) { $settings = (Resolve-Path -LiteralPath $MavenSettings -ErrorAction Stop).Path }
docker version --format '{{.Server.Version}}'
if ($LASTEXITCODE -ne 0) { throw 'Start Docker Desktop Linux engine first.' }
docker image inspect flashguard-mq-test-runtime:local *> $null
if ($LASTEXITCODE -ne 0 -and $Mode -in @('Preflight','Full')) {
    docker build -f (Join-Path $projectRoot 'config\rocketmq\Dockerfile.test-runtime') -t flashguard-mq-test-runtime:local (Join-Path $projectRoot 'config\rocketmq')
    if ($LASTEXITCODE -ne 0) { throw 'RocketMQ runtime build failed.' }
}
$arguments = @('-Dflashguard.test.db.url=')
if (Test-Path -LiteralPath $settings -PathType Leaf) { $arguments += @('-s', $settings) }
switch ($Mode) {
    'Preflight' { $arguments += @('-Dtest=RealHttpBenchmarkTest','-Dflashguard.perf.enabled=true','-Dperf.warmup.seconds=0','-Dperf.seconds=5','-Dperf.repetitions=1',"-Dperf.rate=$Rate","-Dperf.vus=$Vus") }
    'Full' { $arguments += @('-Dtest=RealHttpBenchmarkTest','-Dflashguard.perf.enabled=true','-Dperf.warmup.seconds=60','-Dperf.seconds=180','-Dperf.repetitions=5',"-Dperf.rate=$Rate","-Dperf.vus=$Vus") }
    'Sql' { $arguments += @('-Dtest=HistoricalOrdersSqlBenchmarkTest','-Dflashguard.sql-benchmark.enabled=true') }
    'Cluster' { $arguments += '-Dtest=RedisClusterBucketBenchmarkTest' }
}
Push-Location $projectRoot
try {
    & mvn @arguments test
    if ($LASTEXITCODE -ne 0) { throw 'Benchmark failed. Keep raw results and inspect their failure gates.' }
} finally { Pop-Location }
Write-Host 'Finished. Read benchmark/results or benchmark/datasets/results; preflight is REVIEW, not capacity acceptance.'
