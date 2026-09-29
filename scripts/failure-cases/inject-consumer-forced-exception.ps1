[CmdletBinding()]
param()
& (Join-Path $PSScriptRoot '..\verify-real-chain.ps1') -Scenario ConsumerFailure
