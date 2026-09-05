[CmdletBinding()]
param(
    [ValidateNotNullOrEmpty()]
    [string]$BaseUrl = "http://localhost:8080",

    [ValidateRange(1, 600)]
    [int]$StartupTimeoutSeconds = 60
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$activityId = [long]900000001
$activityName = "__FLASHGUARD_BASELINE_CHECK_ONLY__"
$requestCount = 1000
$initialStock = 100
$expectedSoldOut = 900
$userIdBase = [long]1000000
$projectDirectory = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$composeFile = Join-Path $projectDirectory "compose.yml"

function Invoke-ComposeSql {
    param(
        [Parameter(Mandatory)]
        [string]$Sql,

        [Parameter(Mandatory)]
        [string]$ComposeFile,

        [Parameter(Mandatory)]
        [string]$ProjectDirectory
    )

    $mysqlCommand = @'
MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --user="$MYSQL_USER" --database="$MYSQL_DATABASE" --connect-timeout=10 --batch --raw --skip-column-names
'@
    $dockerArguments = @(
        "compose",
        "--project-directory", $ProjectDirectory,
        "--file", $ComposeFile,
        "exec", "-T", "mysql",
        "sh", "-eu", "-c", $mysqlCommand
    )

    $result = $Sql | & docker @dockerArguments
    $dockerExitCode = $LASTEXITCODE
    if ($dockerExitCode -ne 0) {
        throw "MySQL command through docker compose failed with exit code $dockerExitCode."
    }

    return @($result)
}

function Add-Failure {
    param(
        [Parameter(Mandatory)]
        [AllowEmptyCollection()]
        [System.Collections.Generic.List[string]]$Failures,

        [Parameter(Mandatory)]
        [string]$Message
    )

    $Failures.Add($Message)
}

function Get-FailureSampleText {
    param(
        [Parameter(Mandatory)]
        [AllowEmptyCollection()]
        [System.Collections.Generic.List[string]]$Failures
    )

    $sampleCount = [Math]::Min(3, $Failures.Count)
    return $Failures.GetRange(0, $sampleCount) -join "; "
}

function Get-BodyPreview {
    param([AllowNull()][string]$Body)

    if ([string]::IsNullOrWhiteSpace($Body)) {
        return "<empty>"
    }

    $singleLine = $Body -replace "\s+", " "
    if ($singleLine.Length -le 160) {
        return $singleLine
    }

    return $singleLine.Substring(0, 160) + "..."
}

$handler = $null
$client = $null
$requestContents = [System.Collections.Generic.List[System.Net.Http.HttpContent]]::new()
$responses = [System.Collections.Generic.List[System.Net.Http.HttpResponseMessage]]::new()
$exitCode = 0

try {
    if (-not (Test-Path -LiteralPath $composeFile -PathType Leaf)) {
        throw "Compose file not found: $composeFile"
    }

    $normalizedBaseUrl = $BaseUrl.TrimEnd([char]'/')
    $baseUri = $null
    if (-not [System.Uri]::TryCreate(
        $normalizedBaseUrl,
        [System.UriKind]::Absolute,
        [ref]$baseUri
    ) -or $baseUri.Scheme -notin @("http", "https")) {
        throw "BaseUrl must be an absolute HTTP or HTTPS URL: $BaseUrl"
    }

    $handler = [System.Net.Http.SocketsHttpHandler]::new()
    $handler.MaxConnectionsPerServer = $requestCount
    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(60)

    # A random, read-only lookup proves that the application and its database are ready.
    $probeRequestId = "baseline-readiness-$([Guid]::NewGuid().ToString('N'))"
    $encodedProbeRequestId = [System.Uri]::EscapeDataString($probeRequestId)
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    $ready = $false
    $lastProbeFailure = "No response received."

    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $probeResponse = $null
        $probeCancellation = [System.Threading.CancellationTokenSource]::new(
            [TimeSpan]::FromSeconds(2)
        )
        try {
            $probeResponse = $client.GetAsync(
                "$normalizedBaseUrl/api/v1/participations/$encodedProbeRequestId",
                $probeCancellation.Token
            ).GetAwaiter().GetResult()
            $probeBodyText = $probeResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            $probeBody = $probeBodyText | ConvertFrom-Json -ErrorAction Stop
            if (
                [int]$probeResponse.StatusCode -eq 404 -and
                $probeBody.code -eq "PARTICIPATION_NOT_FOUND"
            ) {
                $ready = $true
                break
            }

            $lastProbeFailure = "HTTP $([int]$probeResponse.StatusCode), body=$(Get-BodyPreview $probeBodyText)"
        } catch {
            $lastProbeFailure = $_.Exception.Message
        } finally {
            if ($null -ne $probeResponse) {
                $probeResponse.Dispose()
            }
            $probeCancellation.Dispose()
        }

        if ([DateTimeOffset]::UtcNow -lt $deadline) {
            Start-Sleep -Milliseconds 500
        }
    }

    if (-not $ready) {
        throw "FlashGuard did not become ready within $StartupTimeoutSeconds seconds. Last probe: $lastProbeFailure"
    }

    $activityNameSql = $activityName.Replace("'", "''")
    $seedSql = @"
START TRANSACTION;
DELETE o
FROM orders AS o
INNER JOIN activity AS a ON a.id = o.activity_id
WHERE a.id = $activityId AND a.name = '$activityNameSql';
DELETE s
FROM activity_stock AS s
INNER JOIN activity AS a ON a.id = s.activity_id
WHERE a.id = $activityId AND a.name = '$activityNameSql';
DELETE FROM activity
WHERE id = $activityId AND name = '$activityNameSql';
INSERT INTO activity(id, name, start_time, end_time, status)
VALUES (
    $activityId,
    '$activityNameSql',
    UTC_TIMESTAMP(6) - INTERVAL 1 HOUR,
    UTC_TIMESTAMP(6) + INTERVAL 1 HOUR,
    'ACTIVE'
);
INSERT INTO activity_stock(activity_id, initial_stock, available_stock, version)
VALUES ($activityId, $initialStock, $initialStock, 0);
COMMIT;
"@
    $composeSqlArguments = @{
        ComposeFile = $composeFile
        ProjectDirectory = $projectDirectory
    }
    Invoke-ComposeSql -Sql $seedSql @composeSqlArguments | Out-Null

    $runId = [Guid]::NewGuid().ToString("N")
    $operations = [System.Collections.Generic.List[object]]::new()
    for ($index = 0; $index -lt $requestCount; $index++) {
        $userId = $userIdBase + $index
        $requestId = "baseline-$runId-$index"
        $payload = @{
            userId = $userId
            requestId = $requestId
        } | ConvertTo-Json -Compress
        $content = [System.Net.Http.StringContent]::new(
            $payload,
            [System.Text.Encoding]::UTF8,
            "application/json"
        )
        $requestContents.Add($content)
        $task = $client.PostAsync(
            "$normalizedBaseUrl/api/v1/activities/$activityId/participations",
            $content
        )
        $operations.Add([pscustomobject]@{
            Index = $index
            UserId = $userId
            RequestId = $requestId
            Task = $task
        })
    }

    $requestTasks = [System.Threading.Tasks.Task[]]($operations | ForEach-Object { $_.Task })
    try {
        [System.Threading.Tasks.Task]::WhenAll($requestTasks).GetAwaiter().GetResult()
    } catch {
        # WhenAll has now observed every task. Inspect each task below so transport
        # failures remain distinct from valid HTTP business rejections.
    }

    $confirmed = 0
    $soldOut = 0
    $transportFailures = [System.Collections.Generic.List[string]]::new()
    $httpSystemFailures = [System.Collections.Generic.List[string]]::new()
    $unexpectedBusinessRejections = [System.Collections.Generic.List[string]]::new()
    $protocolFailures = [System.Collections.Generic.List[string]]::new()

    foreach ($operation in $operations) {
        if ($operation.Task.IsCanceled) {
            Add-Failure -Failures $transportFailures -Message (
                "user=$($operation.UserId): request timed out or was canceled"
            )
            continue
        }
        if ($operation.Task.IsFaulted) {
            $taskMessages = $operation.Task.Exception.Flatten().InnerExceptions |
                ForEach-Object { $_.Message }
            Add-Failure -Failures $transportFailures -Message (
                "user=$($operation.UserId): $($taskMessages -join ' | ')"
            )
            continue
        }

        $response = $operation.Task.GetAwaiter().GetResult()
        $responses.Add($response)
        try {
            $bodyText = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        } catch {
            Add-Failure -Failures $transportFailures -Message (
                "user=$($operation.UserId): response body read failed: $($_.Exception.Message)"
            )
            continue
        }

        $code = $null
        $jsonFailure = $null
        try {
            $body = $bodyText | ConvertFrom-Json -ErrorAction Stop
            if ($null -ne $body -and $body.PSObject.Properties.Name -contains "code") {
                $code = [string]$body.code
            }
        } catch {
            $jsonFailure = $_.Exception.Message
        }

        $statusCode = [int]$response.StatusCode
        if ($statusCode -eq 201 -and $jsonFailure -eq $null -and $code -eq "SUCCESS") {
            $confirmed++
        } elseif ($statusCode -eq 409 -and $jsonFailure -eq $null -and $code -eq "SOLD_OUT") {
            $soldOut++
        } elseif ($statusCode -ge 400 -and $statusCode -lt 500) {
            Add-Failure -Failures $unexpectedBusinessRejections -Message (
                "user=$($operation.UserId): HTTP $statusCode code=$code body=$(Get-BodyPreview $bodyText)"
            )
        } elseif ($statusCode -ge 500) {
            Add-Failure -Failures $httpSystemFailures -Message (
                "user=$($operation.UserId): HTTP $statusCode code=$code body=$(Get-BodyPreview $bodyText)"
            )
        } else {
            $jsonDetail = if ($null -eq $jsonFailure) { "" } else { "; JSON error=$jsonFailure" }
            Add-Failure -Failures $protocolFailures -Message (
                "user=$($operation.UserId): HTTP $statusCode code=$code body=$(Get-BodyPreview $bodyText)$jsonDetail"
            )
        }
    }

    $requestFailureParts = [System.Collections.Generic.List[string]]::new()
    if ($transportFailures.Count -gt 0) {
        $transportSamples = Get-FailureSampleText -Failures $transportFailures
        $requestFailureParts.Add(
            "transport/system failures=$($transportFailures.Count) [$transportSamples]"
        )
    }
    if ($httpSystemFailures.Count -gt 0) {
        $httpSystemSamples = Get-FailureSampleText -Failures $httpSystemFailures
        $requestFailureParts.Add(
            "HTTP system failures=$($httpSystemFailures.Count) [$httpSystemSamples]"
        )
    }
    if ($unexpectedBusinessRejections.Count -gt 0) {
        $businessSamples = Get-FailureSampleText -Failures $unexpectedBusinessRejections
        $requestFailureParts.Add(
            "unexpected HTTP business rejections=$($unexpectedBusinessRejections.Count) [$businessSamples]"
        )
    }
    if ($protocolFailures.Count -gt 0) {
        $protocolSamples = Get-FailureSampleText -Failures $protocolFailures
        $requestFailureParts.Add(
            "HTTP protocol failures=$($protocolFailures.Count) [$protocolSamples]"
        )
    }
    if ($requestFailureParts.Count -gt 0) {
        throw "Request batch failed: $($requestFailureParts -join ' | ')"
    }

    $querySql = @"
SELECT COUNT(o.id), s.available_stock
FROM activity_stock AS s
LEFT JOIN orders AS o ON o.activity_id = s.activity_id
WHERE s.activity_id = $activityId
GROUP BY s.activity_id, s.available_stock;
"@
    $databaseLines = @(
        Invoke-ComposeSql -Sql $querySql @composeSqlArguments |
            Where-Object { $_ -match "\S" }
    )
    if ($databaseLines.Count -ne 1) {
        throw "Expected one database invariant row, received $($databaseLines.Count)."
    }

    $databaseValues = @($databaseLines[0] -split "`t")
    $orderCount = 0
    $availableStock = 0
    if (
        $databaseValues.Count -ne 2 -or
        -not [int]::TryParse($databaseValues[0], [ref]$orderCount) -or
        -not [int]::TryParse($databaseValues[1], [ref]$availableStock)
    ) {
        throw "Could not parse the database invariant result: $($databaseLines[0])"
    }

    if (
        $confirmed -ne 100 -or
        $soldOut -ne $expectedSoldOut -or
        $orderCount -ne 100 -or
        $availableStock -ne 0
    ) {
        throw "Baseline invariant failed: SUCCESS=$confirmed SOLD_OUT=$soldOut orders=$orderCount stock=$availableStock"
    }

    Write-Output "PASS SUCCESS=$confirmed SOLD_OUT=$soldOut orders=$orderCount stock=$availableStock"
} catch {
    $exitCode = 1
    [Console]::Error.WriteLine("FAIL: $($_.Exception.Message)")
} finally {
    foreach ($response in $responses) {
        $response.Dispose()
    }
    foreach ($content in $requestContents) {
        $content.Dispose()
    }
    if ($null -ne $client) {
        $client.Dispose()
    }
    if ($null -ne $handler) {
        $handler.Dispose()
    }
}

if ($exitCode -ne 0) {
    exit $exitCode
}
