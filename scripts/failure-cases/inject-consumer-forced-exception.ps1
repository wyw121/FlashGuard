[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$ActivityId = "1001",
    [int]$UserId = 30001,
    [int]$Requests = 1,
    [string]$MessageKey = "FORCED_CONSUMER_EXCEPTION"
)

function Invoke-Stage8Request {
    param([string]$RequestId, [int]$Uid)
    $payload = @{ userId = $Uid; requestId = $RequestId } | ConvertTo-Json
    try {
        return Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/activities/$ActivityId/participations" `
            -ContentType "application/json" `
            -Body $payload `
            -TimeoutSec 10
    } catch {
        return $_.Exception.Message
    }
}

function Invoke-Admin {
    param([string]$Uri,[string]$Method="Get",[string]$Body,[hashtable]$Query)
    $queryString = ""
    if ($Query) {
        $parts = @()
        foreach ($k in $Query.Keys) {
            if (-not [string]::IsNullOrWhiteSpace($Query[$k])) {
                $parts += "$k=$([uri]::EscapeDataString($Query[$k]))"
            }
        }
        if ($parts.Count -gt 0) {
            $queryString = "?" + ($parts -join "&")
        }
    }
    try {
        return Invoke-RestMethod -Method $Method -Uri ($BaseUrl + $Uri + $queryString) -TimeoutSec 10
    } catch {
        throw "Admin API call failed for ${Uri}: $($_.Exception.Message)"
    }
}

function Get-ReservationByRequest {
    param([string]$RequestId)
    $resp = Invoke-Admin "/api/v1/admin/faults/reservations/$RequestId"
    return $resp.data
}

$requestId = "stage8-forced-$((Get-Date).ToString('yyyyMMddHHmmssfff'))-$UserId"

Write-Host "1) Create one participation request and keep it in pending state if available"
foreach ($i in 1..$Requests) {
    $uid = $UserId + $i - 1
    $rid = "$requestId-$i"
    $resp = Invoke-Stage8Request -RequestId $rid -Uid $uid
    Write-Host "   requestId=$rid status=$($resp.data.status)"
}

Write-Host "2) Open reservation detail for a request"
$reservation = Get-ReservationByRequest -RequestId "$requestId-1"
if (-not $reservation.reservationId) {
    throw "No reservation found for $requestId. Check if reservation was created and database has durable row."
}
Write-Host "   reservationId=$($reservation.reservationId) status=$($reservation.status)"

Write-Host "3) Enable forced-consumer-exception flag"
Invoke-Admin "/api/v1/admin/faults/consumer/forced-exception" "Post" "" @{ enabled = "true" }

Write-Host "4) Replay consume by requestId should fail (expected)"
try {
    Invoke-Admin "/api/v1/admin/faults/consumer/replay" "Post" "" @{ requestId = $requestId; messageKey = $MessageKey } | Out-Null
    Write-Host "   unexpected: consume succeeded even when forced exception enabled"
} catch {
    Write-Host "   expected failure: $($_)"
}

Write-Host "5) Disable forced-consumer-exception flag"
Invoke-Admin "/api/v1/admin/faults/consumer/forced-exception" "Post" "" @{ enabled = "false" }

Write-Host "6) Replay consume again; should become success and update reservation status"
$retry = Invoke-Admin "/api/v1/admin/faults/consumer/replay" "Post" "" @{ requestId = $requestId; messageKey = $MessageKey }
Write-Host "   orderNo=$($retry.data.orderNo) reservationId=$($retry.data.reservationId)"

Write-Host "7) Inspect backlog metric to confirm drain"
Write-Host "   GET $BaseUrl/actuator/metrics/flashguard_mq_backlog"
