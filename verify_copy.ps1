# One-shot verification for debug overlay copy button (English-only: PS 5.1 reads BOM-less as ANSI)
# usage: powershell -ExecutionPolicy Bypass -File verify_copy.ps1
$ErrorActionPreference = "SilentlyContinue"
$env:PATH = $env:PATH

function Dump-Ui([string]$name) {
    for ($i = 1; $i -le 6; $i++) {
        $r = adb shell uiautomator dump /sdcard/$name.xml 2>&1 | Out-String
        if ($r -match 'dumped to') {
            adb pull /sdcard/$name.xml (Join-Path $env:TEMP "$name.xml") 2>$null | Out-Null
            $p = Join-Path $env:TEMP "$name.xml"
            if ((Test-Path $p) -and ((Get-Item $p).Length -gt 500)) {
                return (Get-Content $p -Raw -Encoding UTF8)
            }
        }
        Start-Sleep -Milliseconds 700
    }
    return ""
}

function SwitchInfo([string]$xml) {
    $res = @()
    foreach ($m in [regex]::Matches($xml, '<node[^>]*class="android\.widget\.Switch"[^>]*>')) {
        $tag = $m.Value
        $rid = ([regex]::Match($tag, 'resource-id="([^"]*)"')).Groups[1].Value
        $ck = ([regex]::Match($tag, 'checked="([^"]*)"')).Groups[1].Value
        $b = [regex]::Match($tag, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        $cx = [int](([int]$b.Groups[1].Value + [int]$b.Groups[3].Value) / 2)
        $cy = [int](([int]$b.Groups[2].Value + [int]$b.Groups[4].Value) / 2)
        $res += [pscustomobject]@{ Rid = $rid; Checked = $ck; X = $cx; Y = $cy }
    }
    return $res
}

Write-Output "== open settings =="
adb shell am start -n com.wisight.adauto/.MainActivity 2>&1 | Out-Null
Start-Sleep -Seconds 2
# scroll to top
adb shell input swipe 800 600 800 2200 300 | Out-Null
Start-Sleep -Milliseconds 800

$xml = Dump-Ui "vc1"
$sw = SwitchInfo $xml
foreach ($s in $sw) { Write-Output ("  " + $s.Rid + " checked=" + $s.Checked + " @" + $s.X + "," + $s.Y) }

foreach ($s in $sw) {
    if (($s.Rid -like "*switchSkip" -or $s.Rid -like "*switchDebugOverlay") -and $s.Checked -eq "false") {
        Write-Output ("  tap " + $s.Rid)
        adb shell input tap $s.X $s.Y | Out-Null
        Start-Sleep -Milliseconds 800
    }
}
$xml2 = Dump-Ui "vc2"
foreach ($s in (SwitchInfo $xml2)) { Write-Output ("  after: " + $s.Rid + " checked=" + $s.Checked) }

Write-Output "== open hongguo =="
adb shell input keyevent 3 | Out-Null
Start-Sleep -Seconds 1
adb shell monkey -p com.phoenix.read -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null
Start-Sleep -Seconds 6

$png = Join-Path $env:TEMP "vc_overlay.png"
cmd /c "adb exec-out screencap -p > `"$png`"" 2>$null
if ((Test-Path $png) -and ((Get-Item $png).Length -gt 100000)) {
    Write-Output ("screenshot ok: " + [math]::Round((Get-Item $png).Length / 1KB) + "KB -> $png")
} else {
    Write-Output "screenshot FAILED"
}

adb logcat -c
adb shell input tap 1049 235 | Out-Null
Start-Sleep -Seconds 2
$log = Join-Path $env:TEMP "vc_copy.log.txt"
cmd /c "adb logcat -d -s AdAutoBall:V > `"$log`"" 2>$null
if (Test-Path $log) {
    $l = Get-Content $log -Encoding UTF8
    Write-Output ("tap copy -> 'debug detail copied' lines: " + (($l | Select-String -Pattern 'debug detail copied').Count))
    $l | Select-Object -Last 5 | ForEach-Object { Write-Output ("  " + $_) }
}
