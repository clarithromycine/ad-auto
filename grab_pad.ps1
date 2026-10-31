# Grab helper with retries (MIUI pad adb link is flaky). English only: PS 5.1 reads
# BOM-less files as ANSI, so non-ASCII text would break parsing.
# usage: powershell -ExecutionPolicy Bypass -File grab_pad.ps1 <tag>
param([string]$tag = "pad")

$ErrorActionPreference = "SilentlyContinue"

function Wait-Device([int]$maxTry = 20) {
    for ($i = 1; $i -le $maxTry; $i++) {
        $list = (adb devices 2>$null) -join "`n"
        if ($list -match "\tdevice") { return $true }
        adb reconnect offline 2>$null | Out-Null
        Start-Sleep -Milliseconds 800
    }
    return $false
}

$out = Join-Path $env:TEMP $tag
Write-Output "== wait device =="
if (-not (Wait-Device)) { Write-Output "device not ready"; exit 1 }

$focus = ""
for ($i = 1; $i -le 6; $i++) {
    $focus = (adb shell dumpsys window 2>$null | Select-String -Pattern 'mCurrentFocus' | Select-Object -First 1)
    if ($focus) { $focus = $focus.ToString() }
    if ($focus -and $focus -notmatch 'null') { break }
    Start-Sleep -Milliseconds 600
}
Write-Output ("focus: " + $focus)

$png = "$out.png"
$okPng = $false
for ($i = 1; $i -le 8; $i++) {
    cmd /c "adb exec-out screencap -p > `"$png`"" 2>$null
    if ((Test-Path $png) -and ((Get-Item $png).Length -gt 100000)) { $okPng = $true; break }
    Wait-Device 6 | Out-Null
    Start-Sleep -Milliseconds 500
}
if ($okPng) { Write-Output ("png: " + [math]::Round((Get-Item $png).Length / 1KB) + "KB") }
else { Write-Output "png: FAILED" }

$log = "$out.log.txt"
$okLog = $false
for ($i = 1; $i -le 6; $i++) {
    cmd /c "adb logcat -d -s AdDetector:V > `"$log`"" 2>$null
    if ((Test-Path $log) -and ((Get-Item $log).Length -gt 100)) { $okLog = $true; break }
    Wait-Device 6 | Out-Null
    Start-Sleep -Milliseconds 500
}
if ($okLog) { Write-Output ("log lines: " + (Get-Content $log -Encoding UTF8).Count) }
else { Write-Output "log: FAILED" }

$xml = "$out.xml"
$okXml = $false
for ($i = 1; $i -le 8; $i++) {
    $r = adb shell uiautomator dump /sdcard/$tag.xml 2>&1 | Out-String
    if ($r -match 'dumped to') {
        adb pull /sdcard/$tag.xml $xml 2>$null | Out-Null
        if ((Test-Path $xml) -and ((Get-Item $xml).Length -gt 500)) { $okXml = $true; break }
    }
    Start-Sleep -Milliseconds 700
}
if ($okXml) { Write-Output ("dump: " + [math]::Round((Get-Item $xml).Length / 1KB) + "KB") }
else { Write-Output "dump: FAILED" }

Write-Output ("files: $png  $log  $xml")
