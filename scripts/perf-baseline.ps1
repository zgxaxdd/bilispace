# BBSpace performance baseline measurement.
#
# Measures home-feed scroll frame timing so each optimization can be judged
# against real numbers instead of guesswork.
#
# Usage (PowerShell, from repo root):
#   .\scripts\perf-baseline.ps1 -Pkg com.naaammme.bbspace -Rounds 4
#
# Budgets (60fps => 16.67ms):
#   jank <= 0.80%   handover red line
#   p95  <= 8ms     handover red line (scroll surfaces)
#   p50  <= 8ms     rule of thumb: lower median = more responsive feel
#
# Notes:
# - Always warm up first; first-run image decode / font load pollutes early rounds.
# - Counters are reset per round so rounds do not accumulate.
# - Device must be awake and sitting on the home feed; this script does not navigate.

param(
    [string]$Pkg = "com.naaammme.bbspace",
    [int]$Rounds = 3,
    [int]$ScrollsPerRound = 8,
    [string]$Adb = "D:\env\android-sdk\platform-tools\adb.exe",
    [switch]$SkipWarmup
)

$ErrorActionPreference = "Stop"

# IMPORTANT: never declare the dumpsys line array as [string].
# PowerShell would join the lines into one string, the `^` anchors would stop
# matching, and every metric would silently read "-" (far worse than an error).
# Hence: no typed param, plain regex matching on the raw array.
function Stat {
    param($Lines, $Pattern)
    $hit = @($Lines | Where-Object { $_ -match $Pattern })
    if ($hit.Count -eq 0) { return "-" }
    return ($hit[0] -replace '^[^:]*:\s*', '').Trim()
}

Write-Host "=== BBSpace performance baseline ==="
Write-Host "package: $Pkg   rounds: $Rounds   scrolls/round: $ScrollsPerRound"
Write-Host ""

if (-not $SkipWarmup) {
    Write-Host "warming up (discarding first 6 scrolls)..."
    for ($i = 0; $i -lt 6; $i++) {
        & $Adb shell input swipe 636 2100 636 900 260 | Out-Null
        Start-Sleep -Milliseconds 400
    }
    Start-Sleep -Seconds 3
}

$rows = @()
for ($r = 1; $r -le $Rounds; $r++) {
    & $Adb shell dumpsys gfxinfo $Pkg reset | Out-Null
    for ($i = 0; $i -lt $ScrollsPerRound; $i++) {
        & $Adb shell input swipe 636 2100 636 900 260 | Out-Null
        Start-Sleep -Milliseconds 420
    }
    Start-Sleep -Seconds 2

    $o = @(& $Adb shell dumpsys gfxinfo $Pkg)

    $frames = Stat -Lines $o -Pattern '^Total frames rendered'
    $jank = Stat -Lines $o -Pattern '^Janky frames:'
    $p50 = Stat -Lines $o -Pattern '^50th percentile'
    $p90 = Stat -Lines $o -Pattern '^90th percentile'
    $p95 = Stat -Lines $o -Pattern '^95th percentile'
    $p99 = Stat -Lines $o -Pattern '^99th percentile'
    $slowDraw = Stat -Lines $o -Pattern '^Number Slow issue draw commands'
    $slowUi = Stat -Lines $o -Pattern '^Number Slow UI thread'
    $missed = Stat -Lines $o -Pattern '^Number Missed Vsync'

    $rows += [pscustomobject]@{
        Round = "r$r"; Frames = $frames; Jank = $jank
        P50 = $p50; P90 = $p90; P95 = $p95; P99 = $p99
        SlowDraw = $slowDraw; SlowUi = $slowUi; MissedVsync = $missed
    }

    Write-Host ("[r{0}] frames={1} jank={2} p50={3} p90={4} p95={5} p99={6} | slowDraw={7} slowUi={8} missedVsync={9}" -f `
        $r, $frames, $jank, $p50, $p90, $p95, $p99, $slowDraw, $slowUi, $missed)
}

Write-Host ""
Write-Host "=== summary ==="
$rows | Format-Table -AutoSize
