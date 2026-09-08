[CmdletBinding()]
param(
    [int]$GraceSeconds = 2
)

$java = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -like "*com.flashguard.FlashGuardApplication*" } |
    Select-Object -First 1

if (-not $java) {
    throw "FlashGuard Java process not found."
}

Write-Host "Kill producer node process PID=$($java.ProcessId)"
Stop-Process -Id $java.ProcessId -Force
Write-Host "Process killed, wait $GraceSeconds seconds"
Start-Sleep -Seconds $GraceSeconds
Write-Host "Producer-kill fault completed. Start app process before resume."
