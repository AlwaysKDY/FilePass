#!/usr/bin/env pwsh
# FilePass 一键打包脚本
# 运行后 APK 和 EXE 会直接输出到 D:\FilePass\ 目录下

$ROOT = $PSScriptRoot
$OUT_APK = Join-Path $ROOT "FilePass.apk"
$OUT_EXE = Join-Path $ROOT "FilePass.exe"

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  FilePass 打包" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# ── 1. 编译 Android APK ───────────────────────────────────────
Write-Host ""
Write-Host "[1/2] 编译 Android APK..." -ForegroundColor Yellow

$gradleDir = Join-Path $ROOT "android-client"
Push-Location $gradleDir
try {
    & .\gradlew.bat assembleDebug 2>&1 | Where-Object { $_ -match "BUILD|ERROR|error:|warning:" }
    if ($LASTEXITCODE -ne 0) { throw "Gradle 编译失败" }
} finally { Pop-Location }

$srcApk = Join-Path $gradleDir "app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $srcApk) {
    Copy-Item $srcApk $OUT_APK -Force
    $apkSize = [math]::Round((Get-Item $OUT_APK).Length / 1MB, 1)
    Write-Host "  ✓ APK 已输出: $OUT_APK ($apkSize MB)" -ForegroundColor Green
} else {
    Write-Host "  ✗ APK 未找到: $srcApk" -ForegroundColor Red
}

# ── 2. 打包 PC 端 EXE ────────────────────────────────────────
Write-Host ""
Write-Host "[2/2] 打包 PC 端 EXE (PyInstaller)..." -ForegroundColor Yellow

$serverDir = Join-Path $ROOT "pc-server"
Push-Location $serverDir
try {
    python -m PyInstaller FilePass.spec --noconfirm 2>&1 | Where-Object { $_ -match "INFO|WARNING|ERROR|Building" }
    if ($LASTEXITCODE -ne 0) { throw "PyInstaller 打包失败" }
} finally { Pop-Location }

$srcExe = Join-Path $serverDir "dist\FilePass.exe"
if (Test-Path $srcExe) {
    Copy-Item $srcExe $OUT_EXE -Force
    $exeSize = [math]::Round((Get-Item $OUT_EXE).Length / 1MB, 1)
    Write-Host "  ✓ EXE 已输出: $OUT_EXE ($exeSize MB)" -ForegroundColor Green
} else {
    Write-Host "  ✗ EXE 未找到: $srcExe" -ForegroundColor Red
}

# ── 完成 ─────────────────────────────────────────────────────
Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  打包完成！" -ForegroundColor Cyan
Write-Host "  APK → $OUT_APK" -ForegroundColor White
Write-Host "  EXE → $OUT_EXE" -ForegroundColor White
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
