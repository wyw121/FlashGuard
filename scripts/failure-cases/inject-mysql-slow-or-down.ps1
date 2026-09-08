[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$ActivityId = "1001",
    [string]$Mode = "down",
    [string]$UserId = "20002"
)

$mysqlUser = if ([string]::IsNullOrWhiteSpace($env:FLASHGUARD_DB_USERNAME)) { "flashguard" } else { $env:FLASHGUARD_DB_USERNAME }
$mysqlPassword = if ([string]::IsNullOrWhiteSpace($env:FLASHGUARD_DB_PASSWORD)) { "flashguard-dev" } else { $env:FLASHGUARD_DB_PASSWORD }
$mysqlDatabase = if ([string]::IsNullOrWhiteSpace($env:FLASHGUARD_DB_DATABASE)) { "flashguard" } else { $env:FLASHGUARD_DB_DATABASE }

function Invoke-Stage8Request {
    param([string]$RequestId)
    try {
        Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/activities/$ActivityId/participations" `
            -ContentType "application/json" `
            -Body (ConvertTo-Json @{ userId = [int]$UserId; requestId = $RequestId }) `
            -TimeoutSec 10
    } catch {
        $_.Exception.Message
    }
}

if ($Mode -eq "down") {
    Write-Host "Stop mysql"
    try {
        docker compose stop mysql
        Invoke-Stage8Request -RequestId "stage8-mysql-down-$(Get-Date -Format yyyyMMddHHmmssfff)"
    } finally {
        docker compose start mysql
    }
} else {
    Write-Host "Hold mysql row lock to simulate slow DB path"
    $lockSql = "START TRANSACTION; UPDATE activity_stock SET version = version WHERE activity_id = $ActivityId; DO SLEEP(60); COMMIT;"
    $lockJob = Start-Job -ScriptBlock {
        param($sql, $user, $password, $db)
        docker compose exec -T mysql mysql -u"$user" -p"$password" "$db" -e "$sql"
    } -ArgumentList $lockSql, $mysqlUser, $mysqlPassword, $mysqlDatabase

    Start-Sleep -Seconds 2
    Invoke-Stage8Request -RequestId "stage8-mysql-slow-$(Get-Date -Format yyyyMMddHHmmssfff)"
    Stop-Job -Id $lockJob.Id | Out-Null
}
