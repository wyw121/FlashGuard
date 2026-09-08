[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$ActivityId = "1001",
    [string]$UserId = "20001"
)

function Invoke-Stage8Request {
    param([string]$RequestId)
    try {
        Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/activities/$ActivityId/participations" `
            -ContentType "application/json" `
            -Body (ConvertTo-Json @{ userId = [int]$UserId; requestId = $RequestId }) `
            -TimeoutSec 5
    } catch {
        $_.Exception.Message
    }
}

Write-Host "1) Stop redis"
docker compose stop redis

try {
    Write-Host "2) Trigger participation request during Redis outage"
    Invoke-Stage8Request -RequestId "stage8-redis-unavailable-$(Get-Date -Format yyyyMMddHHmmssfff)"
    Invoke-Stage8Request -RequestId "stage8-redis-unavailable-$(Get-Date -Format yyyyMMddHHmmssfff)-repeat"
} finally {
    Write-Host "3) Start redis back"
    docker compose start redis
}

Write-Host "4) Validate one request in normal mode"
Invoke-Stage8Request -RequestId "stage8-redis-after-recovery-$(Get-Date -Format yyyyMMddHHmmssfff)"
