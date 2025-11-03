# PowerShell script to generate a test video file for ARR projects
# This creates a configurable MP4 file that can be used with the local video feature

param(
    [string]$OutputPath = "C:\temp\debridav-test-video.mp4",
    [string]$Preset = "SMALL_720P"
)

Write-Host "🎬 Generating test video file for ARR projects..." -ForegroundColor Green
Write-Host "📁 Output path: $OutputPath" -ForegroundColor Cyan
Write-Host "🎯 Preset: $Preset" -ForegroundColor Cyan

# Create parent directory if it doesn't exist
$parentDir = Split-Path $OutputPath -Parent
if (!(Test-Path $parentDir)) {
    New-Item -ItemType Directory -Path $parentDir -Force | Out-Null
}

# Parse preset to get resolution, duration, and bitrate
switch ($Preset.ToUpper()) {
    "MINIMAL" {
        $Resolution = "1x1"
        $Duration = "0.1"
        $Bitrate = "500k"
    }
    "SMALL_720P" {
        $Resolution = "1280x720"
        $Duration = "8"
        $Bitrate = "1000k"
    }
    "SMALL_1080P" {
        $Resolution = "1920x1080"
        $Duration = "4"
        $Bitrate = "2000k"
    }
    "MEDIUM_720P" {
        $Resolution = "1280x720"
        $Duration = "40"
        $Bitrate = "2000k"
    }
    "MEDIUM_1080P" {
        $Resolution = "1920x1080"
        $Duration = "20"
        $Bitrate = "4000k"
    }
    "LARGE_720P" {
        $Resolution = "1280x720"
        $Duration = "100"
        $Bitrate = "4000k"
    }
    "LARGE_1080P" {
        $Resolution = "1920x1080"
        $Duration = "50"
        $Bitrate = "8000k"
    }
    default {
        Write-Host "⚠️  Unknown preset: $Preset, using SMALL_720P" -ForegroundColor Yellow
        $Resolution = "1280x720"
        $Duration = "8"
        $Bitrate = "1000k"
    }
}

# Method 1: Try FFmpeg first (if available)
$ffmpegPath = Get-Command ffmpeg -ErrorAction SilentlyContinue
if ($ffmpegPath) {
    Write-Host "✅ FFmpeg found, creating video with preset: $Preset" -ForegroundColor Green
    try {
        if ($Preset.ToUpper() -eq "MINIMAL") {
            # Create minimal MP4 structure
            $minimalMp4 = [byte[]]@(
                0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D,
                0x00, 0x00, 0x00, 0x00, 0x69, 0x73, 0x6F, 0x6D, 0x69, 0x73, 0x6F, 0x32,
                0x6D, 0x70, 0x34, 0x31, 0x00, 0x00, 0x00, 0x08, 0x6D, 0x6F, 0x6F, 0x76,
                0x00, 0x00, 0x00, 0x08, 0x6D, 0x64, 0x61, 0x74
            )
            [System.IO.File]::WriteAllBytes($OutputPath, $minimalMp4)
        } else {
            # Create video with FFmpeg using bitrate control
            $ffmpegArgs = @(
                "-f", "lavfi",
                "-i", "testsrc=duration=$Duration`:size=$Resolution`:rate=25",
                "-c:v", "libx264",
                "-preset", "ultrafast",
                "-b:v", $Bitrate,
                "-maxrate", $Bitrate,
                "-bufsize", ($Bitrate -replace "k", "") + "k",
                "-pix_fmt", "yuv420p",
                "-movflags", "+faststart",
                "-an",
                "-y",
                $OutputPath
            )
            & ffmpeg @ffmpegArgs 2>$null
        }
        Write-Host "✅ Test video generated successfully with FFmpeg" -ForegroundColor Green
    } catch {
        Write-Host "⚠️  FFmpeg failed, creating minimal MP4 structure..." -ForegroundColor Yellow
        # Create minimal MP4 structure
        $minimalMp4 = [byte[]]@(
            0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D,
            0x00, 0x00, 0x00, 0x00, 0x69, 0x73, 0x6F, 0x6D, 0x69, 0x73, 0x6F, 0x32,
            0x6D, 0x70, 0x34, 0x31, 0x00, 0x00, 0x00, 0x08, 0x6D, 0x6F, 0x6F, 0x76,
            0x00, 0x00, 0x00, 0x08, 0x6D, 0x64, 0x61, 0x74
        )
        [System.IO.File]::WriteAllBytes($OutputPath, $minimalMp4)
    }
} else {
    Write-Host "⚠️  FFmpeg not found, creating minimal MP4 structure..." -ForegroundColor Yellow
    
    # Create minimal MP4 structure
    $minimalMp4 = [byte[]]@(
        0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D,
        0x00, 0x00, 0x00, 0x00, 0x69, 0x73, 0x6F, 0x6D, 0x69, 0x73, 0x6F, 0x32,
        0x6D, 0x70, 0x34, 0x31, 0x00, 0x00, 0x00, 0x08, 0x6D, 0x6F, 0x6F, 0x76,
        0x00, 0x00, 0x00, 0x08, 0x6D, 0x64, 0x61, 0x74
    )
    [System.IO.File]::WriteAllBytes($OutputPath, $minimalMp4)
}

$fileSize = (Get-Item $OutputPath).Length
Write-Host "📊 File size: $fileSize bytes" -ForegroundColor Cyan
Write-Host "🎯 MIME type: video/mp4" -ForegroundColor Cyan
Write-Host "📐 Resolution: $Resolution" -ForegroundColor Cyan
Write-Host "⏱️  Duration: ${Duration}s" -ForegroundColor Cyan
Write-Host "📡 Bitrate: $Bitrate" -ForegroundColor Cyan

Write-Host ""
Write-Host "🚀 Configuration for application.properties:" -ForegroundColor Yellow
Write-Host "debridav.enable-rclone-arrs-local-video=true" -ForegroundColor White
Write-Host "debridav.rclone-arrs-local-video-file-path=$OutputPath" -ForegroundColor White
Write-Host ""
Write-Host "✨ Test video file ready for use!" -ForegroundColor Green
