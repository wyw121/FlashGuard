[CmdletBinding()]
param([ValidateSet('Probe','AsyncMixed','Regression')][string]$Mode = 'Probe')
$ErrorActionPreference = 'Stop'
if (-not $IsWindows) { throw 'This optional power-request wrapper is for Windows only.' }
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

# Process-scoped requests; do not change power plans, screen locking, or lid behavior.
if (-not ('FlashGuard.ValidationPowerLease' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
namespace FlashGuard {
    public sealed class ValidationPowerLease : IDisposable {
        [StructLayout(LayoutKind.Sequential)]
        struct PowerStatus {
            public byte ACLineStatus, BatteryFlag, BatteryLifePercent, SystemStatusFlag;
            public uint BatteryLifeTime, BatteryFullLifeTime;
        }
        [StructLayout(LayoutKind.Explicit, Size = 24)]
        struct ReasonData { [FieldOffset(0)] public IntPtr SimpleReasonString; }
        [StructLayout(LayoutKind.Sequential)]
        struct ReasonContext { public uint Version, Flags; public ReasonData Reason; }
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool GetSystemPowerStatus(out PowerStatus status);
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern IntPtr PowerCreateRequest(ref ReasonContext context);
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool PowerSetRequest(IntPtr handle, int type);
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool PowerClearRequest(IntPtr handle, int type);
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool CloseHandle(IntPtr handle);
        IntPtr handle, reason;
        bool system, execution;
        public ValidationPowerLease() {
            if (!Environment.Is64BitProcess) throw new InvalidOperationException("Use 64-bit PowerShell 7.");
            if (!GetSystemPowerStatus(out var status)) throw new Win32Exception();
            if (status.ACLineStatus != 1)
                throw new InvalidOperationException("Connect AC power before long validation; battery/unknown power is not accepted.");
            try {
                reason = Marshal.StringToHGlobalUni("FlashGuard finite validation run; release on process exit");
                var context = new ReasonContext { Version = 0, Flags = 1,
                    Reason = new ReasonData { SimpleReasonString = reason } };
                handle = PowerCreateRequest(ref context);
                if (handle == IntPtr.Zero || handle == new IntPtr(-1)) throw new Win32Exception();
                if (!PowerSetRequest(handle, 1)) throw new Win32Exception();
                system = true;
                if (!PowerSetRequest(handle, 3)) throw new Win32Exception();
                execution = true;
            } catch { Dispose(); throw; }
        }
        public void Dispose() {
            if (handle != IntPtr.Zero && handle != new IntPtr(-1)) {
                if (execution) PowerClearRequest(handle, 3);
                if (system) PowerClearRequest(handle, 1);
                CloseHandle(handle);
            }
            handle = IntPtr.Zero; execution = system = false;
            if (reason != IntPtr.Zero) Marshal.FreeHGlobal(reason);
            reason = IntPtr.Zero;
        }
    }
}
'@
}
$lease = [FlashGuard.ValidationPowerLease]::new()
Write-Host "AC confirmed; temporary system/execution power requests acquired ($Mode)."
Push-Location $projectRoot
try {
    if ($Mode -eq 'Regression') {
        & (Join-Path $PSScriptRoot 'verify-real-chain.ps1')
    } elseif ($Mode -eq 'AsyncMixed') {
        $arguments = @('-Dflashguard.test.db.url=', '-Dtest=RealHttpBenchmarkTest',
            '-Dflashguard.perf.enabled=true', '-Dperf.scenarios=async-mixed',
            '-Dperf.warmup.seconds=60', '-Dperf.seconds=180', '-Dperf.repetitions=5',
            '-Dperf.rate=10', '-Dperf.vus=100')
        $settings = Join-Path (Split-Path $projectRoot -Parent) 'work-notes/maven-settings.xml'
        if (Test-Path -LiteralPath $settings -PathType Leaf) { $arguments += @('-s', $settings) }
        & mvn @arguments test
        if ($LASTEXITCODE -ne 0) { throw 'Async mixed validation failed; preserve its raw results.' }
    }
} finally {
    Pop-Location
    $lease.Dispose()
    Write-Host 'Temporary power requests released; power plan unchanged.'
}
