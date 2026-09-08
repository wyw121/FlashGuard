[CmdletBinding()]
param(
    [int]$GraceSeconds = 2
)

Write-Host "Current single-node stage has not separated consumer process."
Write-Host "This script simulates consumer-kill by restarting FlashGuard main process."

$java = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -like "*com.flashguard.FlashGuardApplication*" } |
    Select-Object -First 1

if (-not $java) {
    throw "FlashGuard Java process not found."
}

Stop-Process -Id $java.ProcessId -Force
Write-Host "Main process killed, wait $GraceSeconds seconds"
Start-Sleep -Seconds $GraceSeconds
Write-Host "Consumer-kill fault completed. Start app process before resume."
