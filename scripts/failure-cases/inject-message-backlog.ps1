[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$ActivityId = "1001",
    [string]$UserBaseId = "30000",
    [int]$Requests = 30
)

function Invoke-Stage8Request {
    param([string]$RequestId, [int]$UserId)
    try {
        Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/activities/$ActivityId/participations" `
            -ContentType "application/json" `
            -Body (ConvertTo-Json @{ userId = $UserId; requestId = $RequestId }) `
            -TimeoutSec 5
    } catch {
        $_.Exception.Message
    }
}

Write-Host "Fire parallel-like submissions to generate pending pressure (Stage 3 consumers are prepared-path placeholders)."
1..$Requests | ForEach-Object {
    $userId = [int]$UserBaseId + $_
    $requestId = "stage8-backlog-$($_)-$(Get-Date -Format yyyyMMddHHmmssfff)"
    Invoke-Stage8Request -RequestId $requestId -UserId $userId | Out-Null
}

Write-Host "Check backlog indicator (if metrics endpoint available): /actuator/metrics/flashguard_mq_backlog"
