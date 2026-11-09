# PowerShell script to generate iOS UI image assets
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$workspace = "F:\AWAKEN\AWAKEN BUSINESS\AWAKEN ENTERPRISE TECHNOLOGY\PROJECT\APP\TRAVEL\SIGHTSEEING TRAVEL"
$edgeExe = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
$iosAssetsDir = Join-Path $workspace "ios\SightseeingTravel\Assets.xcassets"

function Render-SvgToAssetSet([string]$svgPath, [string]$setName, [int]$baseW, [int]$baseH, [string]$bg = "transparent") {
    $svgContent = Get-Content -Raw -Path $svgPath -Encoding utf8
    $setDir = Join-Path $iosAssetsDir "${setName}.imageset"
    if (-not (Test-Path $setDir)) {
        New-Item -ItemType Directory -Force -Path $setDir | Out-Null
    }

    # Render 3x master first
    $w3x = $baseW * 3
    $h3x = $baseH * 3
    $tmpDir = "$env:TEMP\edge_asset_gen_$([System.Guid]::NewGuid())"
    New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null

    $htmlContent = @"
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
html, body { width: ${w3x}px; height: ${h3x}px; overflow: hidden; background: ${bg}; }
svg { width: 100%; height: 100%; display: block; }
</style>
</head>
<body>
$svgContent
</body>
</html>
"@
    $tmpHtml = "$env:TEMP\asset_${setName}.html"
    Set-Content -Path $tmpHtml -Value $htmlContent -Encoding utf8
    $tmpPng = "$env:TEMP\tmp_edge_${setName}_3x.png"
    if (Test-Path $tmpPng) { Remove-Item $tmpPng -Force }
    $png3x = Join-Path $setDir "${setName}@3x.png"

    $p = Start-Process -FilePath $edgeExe -ArgumentList @(
        "--headless",
        "--disable-gpu",
        "--no-sandbox",
        "--user-data-dir=$tmpDir",
        "--window-size=${w3x},${h3x}",
        "--hide-scrollbars",
        "--screenshot=$tmpPng",
        $tmpHtml
    ) -Wait -PassThru -NoNewWindow

    Remove-Item -Path $tmpHtml -Force -ErrorAction SilentlyContinue
    Remove-Item -Path $tmpDir -Recurse -Force -ErrorAction SilentlyContinue

    if (Test-Path $tmpPng) {
        Move-Item -Path $tmpPng -Destination $png3x -Force
    } else {
        throw "Failed to render 3x image for $setName"
    }

    # Downsample to 2x and 1x
    $srcImg = [System.Drawing.Image]::FromFile($png3x)

    function Downscale([string]$destPath, [int]$w, [int]$h) {
        $destBmp = New-Object System.Drawing.Bitmap($w, $h, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        $g = [System.Drawing.Graphics]::FromImage($destBmp)
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
        $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
        $g.DrawImage($srcImg, 0, 0, $w, $h)
        $destBmp.Save($destPath, [System.Drawing.Imaging.ImageFormat]::Png)
        $g.Dispose()
        $destBmp.Dispose()
    }

    Downscale (Join-Path $setDir "${setName}@2x.png") ($baseW * 2) ($baseH * 2)
    Downscale (Join-Path $setDir "${setName}.png") $baseW $baseH
    $srcImg.Dispose()

    # Write Contents.json
    $json = @"
{
  "images" : [
    {
      "idiom" : "universal",
      "filename" : "${setName}.png",
      "scale" : "1x"
    },
    {
      "idiom" : "universal",
      "filename" : "${setName}@2x.png",
      "scale" : "2x"
    },
    {
      "idiom" : "universal",
      "filename" : "${setName}@3x.png",
      "scale" : "3x"
    }
  ],
  "info" : {
    "version" : 1,
    "author" : "xcode"
  }
}
"@
    Set-Content -Path (Join-Path $setDir "Contents.json") -Value $json -Encoding utf8
    Write-Host "Created image asset set: $setName ($baseW x $baseH pt)"
}

$logoDir = Join-Path $workspace "logo\Sightseeing-Travel-Dau-vien-du-Vector"

Render-SvgToAssetSet (Join-Path $logoDir "02-Logo-ngang\SVG\logo-ngang-kem-dao.svg") "LogoHorizontalDark" 232 82
Render-SvgToAssetSet (Join-Path $logoDir "02-Logo-ngang\SVG\logo-ngang-do-gach.svg") "LogoHorizontalLight" 232 82
Render-SvgToAssetSet (Join-Path $logoDir "01-Logo-doc\SVG\logo-doc-do-gach.svg") "LogoVerticalLight" 200 162
Render-SvgToAssetSet (Join-Path $logoDir "03-Bieu-tuong-tem\SVG\tem-do-gach.svg") "StampBadge" 128 128
Render-SvgToAssetSet (Join-Path $logoDir "03-Bieu-tuong-tem\SVG\tem-kem-dao.svg") "StampBadgeInverted" 128 128

Write-Host "All iOS image asset sets created successfully!"
