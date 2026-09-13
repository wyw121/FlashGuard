[CmdletBinding()]
param(
    [ValidateSet('All','RedisOutage','MysqlOutage','ProducerKill','ConsumerKill','ConsumerFailure','Backlog','Stage6','Stage7')]
    [string]$Scenario = 'All'
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$settingsPath = Join-Path (Split-Path $projectRoot -Parent) 'work-notes\maven-settings.xml'
docker version --format '{{.Server.Version}}'
if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop Linux engine must be running for isolated MySQL/Redis tests.' }
docker image inspect flashguard-mq-test-runtime:local *> $null
if ($LASTEXITCODE -ne 0) {
    docker build -f (Join-Path $projectRoot 'config\rocketmq\Dockerfile.test-runtime') -t flashguard-mq-test-runtime:local (Join-Path $projectRoot 'config\rocketmq')
    if ($LASTEXITCODE -ne 0) { throw 'Unable to build isolated RocketMQ test runtime.' }
}
$tests = switch ($Scenario) {
    'RedisOutage' { 'RocketMqIntegrationTest#pausedOwnedRedisFailsClosedWithoutDurableOrder' }
    'MysqlOutage' { 'RocketMqIntegrationTest#pausedOwnedMysqlCausesBrokerRetryThenRecovers' }
    'ProducerKill' { 'RocketMqIntegrationTest#producerProcessHaltBeforeLocalTransactionRollsBack+producerProcessHaltAfterLocalCommitRecovers' }
    'ConsumerKill' { 'RocketMqIntegrationTest#consumerProcessHaltBeforeCommitRedelivers' }
    'ConsumerFailure' { 'RocketMqIntegrationTest#persistentConsumerFailureReachesRealDlqThenTimeoutCompensates' }
    'Backlog' { 'RocketMqIntegrationTest#stoppedConsumerRestartsAndDrainsBrokerBacklog' }
    'Stage6' { 'ActivityCacheServiceTest,ActivityCacheConcurrencyTest,ActivityCacheRedisTest,ParticipationRateLimiterTest,ParticipationRateLimiterRedisTest' }
    'Stage7' { 'RedisHotKeyBenchmarkTest,RedisClusterBucketBenchmarkTest' }
    default { $null }
}
$mavenArgs = @('-s', $settingsPath, '-f', (Join-Path $projectRoot 'pom.xml'), '-Dflashguard.test.db.url=')
if ($tests) { $mavenArgs += "-Dtest=$tests" }
$mavenArgs += 'verify'
& mvn @mavenArgs
if ($LASTEXITCODE -ne 0) { throw "FAIL: $Scenario. Inspect target/surefire-reports and target/rocketmq-it." }
Write-Host "PASS: $Scenario selected tests and packaging. Inspect reports for exact scope; this is not production capacity acceptance."
