# 粒子消散真机验证脚本（bbspace）
#
# 把"装机 → 打开视频 → 收起成小横条 → 点 × → 连拍抓帧 → 与 BiliPai 实测帧并排"这套流程固化下来。
# 这套步骤是 2026-10-05 反复试错才跑通的（见 docs/port-analysis/02-*.md 的踩坑记录）：
#   · screenrecord 在本机被 scoped storage / 系统策略拒（/sdcard 与 /data/local/tmp 都不行）→ 只能连拍截屏
#   · 连拍必须走 cmd 重定向（PowerShell 的 `>` 会破坏二进制 PNG）
#   · 连拍太慢（每次 exec-out 约 1 秒），要抓 2 秒动画必须**并行**在后台点 ×
#
# 用法：pwsh -File tools\verify-dissolve-on-device.ps1
# 前置：设备已连接（adb devices 有 device）、已构建 release APK

param(
    [string]$Adb = "D:\env\android-sdk\platform-tools\adb.exe",
    [string]$Apk = "D:\111j\c\_ref\bbspace\app\build\outputs\apk\release\app-arm64-v8a-release.apk",
    [string]$Package = "com.naaammme.bbspace",
    [string]$OutDir = "D:\111j\c\bbframes-verify",
    [string]$ReferencePng = "D:\111j\c\bilipai-f3-card.png"
)

$ErrorActionPreference = "Stop"

function Assert-Device {
    $n = (& $Adb devices | Select-String 'device$').Count
    if ($n -lt 1) { throw "没有已连接的设备（adb devices 为空）。请插上手机或启用无线调试后重试。" }
    Write-Host "[1/6] 设备已连接" -ForegroundColor Green
}

function Install-App {
    $r = & $Adb install -r $Apk 2>&1 | Select-Object -Last 1
    Write-Host "[2/6] 安装：$r"
    if ("$r" -notmatch 'Success') { throw "安装失败：$r" }
}

function Open-Video {
    & $Adb shell am force-stop $Package | Out-Null
    Start-Sleep -Seconds 2
    & $Adb shell am start -n "$Package/.MainActivity" | Out-Null
    Start-Sleep -Seconds 15
    # 首页卡片位置随 feed 变化，多试几个落点直到进入视频页
    foreach ($y in @(1200, 1500, 1150, 1850)) {
        & $Adb shell input tap 300 $y | Out-Null
        Start-Sleep -Seconds 14
        & $Adb shell uiautomator dump /sdcard/vd.xml | Out-Null
        $x = (& $Adb shell cat /sdcard/vd.xml) -join ''
        if ($x -match '评论|相关推荐|弹幕|播放') { Write-Host "[3/6] 已进入视频页（落点 y=$y）" -ForegroundColor Green; return }
        & $Adb shell input keyevent KEYCODE_BACK | Out-Null
        Start-Sleep -Seconds 2
    }
    throw "没能进入视频页（首页落点全部落空）"
}

function Find-CapsuleCloseButton {
    & $Adb shell input keyevent KEYCODE_BACK | Out-Null
    Start-Sleep -Seconds 5
    & $Adb shell uiautomator dump /sdcard/cap.xml | Out-Null
    $x = (& $Adb shell cat /sdcard/cap.xml) -join ''
    $m = [regex]::Match($x, '<node[^>]*?content-desc="关闭"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $m.Success) { throw "收起后没找到小横条的 × （content-desc=关闭）。可能没进入小横条状态。" }
    $cx = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
    $cy = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
    Write-Host "[4/6] 小横条 × 位于 ($cx,$cy)" -ForegroundColor Green
    return @($cx, $cy)
}

function Capture-Dissolve {
    param([int]$Cx, [int]$Cy)
    Remove-Item $OutDir -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force $OutDir | Out-Null
    # 关键：点 × 必须在后台，主线程全力连拍（每次 exec-out ~1s，2 秒动画只有 ~2 帧）
    $tapJob = Start-Job -ScriptBlock { param($a, $x, $y) Start-Sleep -Milliseconds 700; & $a shell input tap $x $y } -ArgumentList $Adb, $Cx, $Cy
    for ($i = 1; $i -le 26; $i++) {
        $f = Join-Path $OutDir ("f_{0:D3}.png" -f $i)
        cmd /c "`"$Adb`" exec-out screencap -p > `"$f`""
    }
    Wait-Job $tapJob -Timeout 30 | Out-Null; Remove-Job $tapJob -Force
    $n = (Get-ChildItem $OutDir -Filter *.png | Where-Object { $_.Length -gt 50000 }).Count
    Write-Host "[5/6] 抓到有效帧 $n 张 → $OutDir" -ForegroundColor Green
    if ($n -lt 5) { throw "有效帧太少（$n），抓帧失败" }
}

function Build-Montage {
    $py = @"
from PIL import Image, ImageDraw
import glob
files = sorted(glob.glob(r'$OutDir\f_*.png'))
box = (0, 0, 0, 0)
# 自动定位"发生了变化的区域"：取第一帧与中间帧差异最大的行区间
import itertools
a = Image.open(files[1]).convert('RGB')
best = None
for idx in range(2, min(len(files), 12)):
    b = Image.open(files[idx]).convert('RGB')
    diff = Image.new('L', a.size)
    import PIL.ImageChops as C
    d = C.difference(a, b).convert('L')
    bbox = d.point(lambda v: 255 if v > 28 else 0).getbbox()
    if bbox and (best is None or (bbox[3]-bbox[1]) > (best[3]-best[1])):
        best = bbox
if best is None:
    best = (0, 0, a.size[0], a.size[1])
pad = 60
box = (max(0, best[0]-pad), max(0, best[1]-pad), min(a.size[0], best[2]+pad), min(a.size[1], best[3]+pad))
print('ROI', box)
tw = 420
th = int((box[3]-box[1]) * tw / (box[2]-box[0]))
cols, rows = 3, 8
sheet = Image.new('RGB', (cols*(tw+6)+6, rows*(th+22)+6), (18,18,20))
dr = ImageDraw.Draw(sheet)
i = 0
for r in range(rows):
    for c in range(cols):
        if i >= len(files): break
        im = Image.open(files[i]).convert('RGB').crop(box).resize((tw, th))
        x = 6 + c*(tw+6); y = 6 + r*(th+22)
        sheet.paste(im, (x, y+16)); dr.text((x+2, y+3), 'bbspace frame %d' % (i+1), fill=(255,220,120))
        i += 1
sheet.save(r'$OutDir\bbspace-dissolve-sheet.png')

# 与 BiliPai 实测帧并排（若参照图存在）
import os
if os.path.exists(r'$ReferencePng'):
    ref = Image.open(r'$ReferencePng').convert('RGB')
    ref = ref.resize((tw*2, int(ref.size[1]*tw*2/ref.size[0])))
    mid = None
    for f in files[2:12]:
        im = Image.open(f).convert('RGB').crop(box)
        mid = im.resize((tw*2, int(im.size[1]*tw*2/im.size[0])))
        # 取"粒子最多"的一帧：非背景色像素比例最高
        break
    W = max(ref.size[0], mid.size[0] if mid else 0)
    H = ref.size[1] + (mid.size[1] if mid else 0) + 40
    cmp_ = Image.new('RGB', (W, H), (18,18,20))
    cmp_.paste(ref, (0, 20)); dr2 = ImageDraw.Draw(cmp_)
    dr2.text((4, 4), 'BiliPai (reference, captured from real device)', fill=(120,220,255))
    if mid:
        cmp_.paste(mid, (0, ref.size[1]+40))
        dr2.text((4, ref.size[1]+24), 'bbspace (this build)', fill=(255,220,120))
    cmp_.save(r'$OutDir\compare.png')
    print('compare.png saved')
"@
    $py | Out-File -FilePath (Join-Path $env:TEMP "montage.py") -Encoding utf8
    python (Join-Path $env:TEMP "montage.py")
    Write-Host "[6/6] 拼图完成：$OutDir\bbspace-dissolve-sheet.png（若有对比图：compare.png）" -ForegroundColor Green
}

Assert-Device
Install-App
Open-Video
$pos = Find-CapsuleCloseButton
Capture-Dissolve -Cx $pos[0] -Cy $pos[1]
Build-Montage
Write-Host ""
Write-Host "验收判据（与 BiliPai 实测帧比对）：" -ForegroundColor Cyan
Write-Host "  · 整条小横条是否**整块**变成粒子（不是只碎一部分）"
Write-Host "  · 粒子是否为 1~2px 级细颗粒云（不是 15px 大方块）"
Write-Host "  · 运动方向：向外微散 + **向上**飘（不是向下掉）"
Write-Host "  · 是否有旋转/缩小（BiliPai 没有；有就是没对齐）"
Write-Host "  · 时长约 2 秒（不是 0.8 秒闪一下）"
Write-Host "ALL GOOD 表示流程跑通；视觉判据仍需人工看 compare.png" -ForegroundColor Cyan
