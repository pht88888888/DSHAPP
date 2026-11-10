# Windows 入口复用 Node 构建器，目标、输入摘要与产物规则只维护一份。
# Node 是本工程已有构建工具；兼容原 PowerShell 参数调用方式。
[CmdletBinding()]
param(
    [string]$Target = "chrome73",
    [string]$CompatVersion = "62",
    [string]$EngineBin = "app/src/main/assets/engine.bin",
    [string]$OutZip = "app/src/main/assets/webview-compat.zip",
    [switch]$DryRun
)
$ErrorActionPreference = "Stop"
$nodeCommand = Get-Command node -ErrorAction Stop
$builderArgs = @(
    (Join-Path $PSScriptRoot "build-webview-compat.mjs"),
    "--target", $Target,
    "--compat-version", $CompatVersion,
    "--engine-bin", $EngineBin,
    "--out-zip", $OutZip
)
if ($DryRun) { $builderArgs += "--dry-run" }
& $nodeCommand.Source @builderArgs
exit $LASTEXITCODE
