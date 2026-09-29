$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$callerDirectory = (Get-Location).Path
$sandbox = Join-Path ([IO.Path]::GetTempPath()) ('flashguard-wrapper-' + [guid]::NewGuid())
$project = Join-Path $sandbox 'standalone checkout'
$scriptDirectory = Join-Path $project 'scripts'
New-Item -ItemType Directory -Path $scriptDirectory -Force | Out-Null
Set-Content (Join-Path $project 'pom.xml') '<project />'
foreach ($name in @('run-benchmarks.ps1', 'verify-real-chain.ps1', 'verify-stage5.ps1')) {
    Copy-Item -LiteralPath (Join-Path $repository "scripts/$name") -Destination $scriptDirectory
}
function global:docker { $global:LASTEXITCODE = 0 }
function global:mvn {
    $global:wrapperArguments = @($args)
    $global:wrapperCwd = (Get-Location).Path
    $global:LASTEXITCODE = 0
}
function Assert-Wrapper([bool]$condition, [string]$message) {
    if (-not $condition) { throw $message }
}
try {
    foreach ($name in @('run-benchmarks.ps1', 'verify-real-chain.ps1', 'verify-stage5.ps1')) {
        $script = Join-Path $scriptDirectory $name
        & $script
        Assert-Wrapper (-not ($global:wrapperArguments -contains '-s')) "$name requires settings absent from a standalone clone"
        Assert-Wrapper ($global:wrapperCwd -eq $project) "$name runs Maven outside the project"
        Assert-Wrapper ((Get-Location).Path -eq $callerDirectory) "$name did not restore caller location"

        $settings = Join-Path $sandbox 'custom settings.xml'
        Set-Content $settings '<settings />'
        & $script -MavenSettings $settings
        $index = [array]::IndexOf($global:wrapperArguments, '-s')
        Assert-Wrapper ($index -ge 0 -and $global:wrapperArguments[$index + 1] -eq $settings) "$name lost explicit settings path"

        $failed = $false
        try { & $script -MavenSettings (Join-Path $sandbox 'missing.xml') } catch { $failed = $true }
        Assert-Wrapper $failed "$name accepted nonexistent explicit settings"
    }
    Write-Host 'PASS: standalone checkout, custom settings with spaces, missing settings, caller directory restoration.'
} finally {
    Remove-Item Function:/docker, Function:/mvn
    # Delete only the unique directory created by this test, under the verified temp root.
    $resolved = [IO.Path]::GetFullPath($sandbox)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected test cleanup path' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
