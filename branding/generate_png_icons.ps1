# PowerShell script to generate PNG icons using headless Edge + System.Drawing high-quality downsampling
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$workspace = "F:\AWAKEN\AWAKEN BUSINESS\AWAKEN ENTERPRISE TECHNOLOGY\PROJECT\APP\TRAVEL\SIGHTSEEING TRAVEL"
$svgPath = Join-Path $workspace "logo\Sightseeing-Travel-Dau-vien-du-Vector\04-Icon-app\SVG\icon-app-nen-do-gach.svg"
$svgContent = Get-Content -Raw -Path $svgPath -Encoding utf8

$edgeExe = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
$masterPng = "$env:TEMP\master_icon_1024.png"
$tmpDir = "$env:TEMP\edge_master_gen"
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null

$htmlContent = @"
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
html, body { width: 1024px; height: 1024px; overflow: hidden; background: #622821; }
svg { width: 100%; height: 100%; display: block; }
</style>
</head>
<body>
$svgContent
</body>
</html>
"@
$tmpHtml = "$env:TEMP\master_1024.html"
Set-Content -Path $tmpHtml -Value $htmlContent -Encoding utf8

if (Test-Path $masterPng) { Remove-Item $masterPng -Force }

Write-Host "Rendering 1024x1024 master icon from SVG via Edge..."
$p = Start-Process -FilePath $edgeExe -ArgumentList @(
    "--headless",
    "--disable-gpu",
    "--no-sandbox",
    "--user-data-dir=$tmpDir",
    "--window-size=1024,1024",
    "--hide-scrollbars",
    "--screenshot=$masterPng",
    $tmpHtml
) -Wait -PassThru -NoNewWindow

if (-not (Test-Path $masterPng)) {
    throw "Failed to render master 1024x1024 PNG from Edge."
}
Write-Host "Master rendered successfully! ($((Get-Item $masterPng).Length) bytes)"
Remove-Item -Path $tmpHtml -Force -ErrorAction SilentlyContinue

$srcImg = [System.Drawing.Image]::FromFile($masterPng)

function Resize-Icon([string]$targetPath, [int]$width, [int]$height) {
    $parent = Split-Path -Parent $targetPath
    if (-not (Test-Path $parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }

    $destBmp = New-Object System.Drawing.Bitmap($width, $height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($destBmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality

    $g.DrawImage($srcImg, 0, 0, $width, $height)
    $destBmp.Save($targetPath, [System.Drawing.Imaging.ImageFormat]::Png)

    $g.Dispose()
    $destBmp.Dispose()
    Write-Host "Created ($width x $height): $targetPath"
}

# Android Icons
$androidRes = Join-Path $workspace "android\app\src\main\res"
$androidDensities = @{
    "mipmap-mdpi" = 48
    "mipmap-hdpi" = 72
    "mipmap-xhdpi" = 96
    "mipmap-xxhdpi" = 144
    "mipmap-xxxhdpi" = 192
}

foreach ($density in $androidDensities.Keys) {
    $sz = $androidDensities[$density]
    $iconPath = Join-Path $androidRes "$density\ic_launcher.png"
    $roundPath = Join-Path $androidRes "$density\ic_launcher_round.png"
    Resize-Icon $iconPath $sz $sz
    Copy-Item -Path $iconPath -Destination $roundPath -Force
}

# Android Play Store 512x512
$playStoreIcon = Join-Path $workspace "branding\playstore-icon-512.png"
Resize-Icon $playStoreIcon 512 512

# iOS Asset Catalog
$iosAppIconDir = Join-Path $workspace "ios\SightseeingTravel\Assets.xcassets\AppIcon.appiconset"
$iosSizes = @(20, 29, 40, 58, 60, 76, 80, 87, 120, 152, 167, 180, 1024)
foreach ($sz in $iosSizes) {
    $iosPath = Join-Path $iosAppIconDir "AppIcon-${sz}x${sz}.png"
    Resize-Icon $iosPath $sz $sz
}

$srcImg.Dispose()
Write-Host "All Android & iOS icons generated successfully!"
