<#
.SYNOPSIS
    Automated deploy, configuration, execution, and log/screenshot capture for Wrecked Metal over ADB.

.DESCRIPTION
    Drives a connected Android device (such as the Galaxy S23 Ultra) from Windows to test Wrecked Metal:
    - Automatically locates adb.exe (PATH, %USERPROFILE%\Android\platform-tools, Android SDK, etc.).
    - Optionally installs a local APK or downloads the latest debug APK from the GitHub "nightly" release.
    - Backs up dd64.ini (or dd64-fidelity.ini) and temporarily disables [cheats] (per AGENTS.md),
      unless -KeepCheats is specified, restoring the original file when finished.
    - Writes temporary DD64_* test switches to Data/env.txt (only if the app has already created Data/).
    - Launches GameActivity directly (debug builds), waits or tracks process exit, captures a screenshot,
      pulls dd64.log, any shot_*.bmp screenshots, and saves filtered logcat output.
    - Plays a Windows notification sound when done (unless -NoAlarm is passed).

.PARAMETER Install
    Install the APK before running the test.

.PARAMETER ApkPath
    Path to a specific .apk file to install with -Install. If omitted, looks for a local Gradle build
    under app/build/outputs/apk/ or falls back to the nightly debug APK.

.PARAMETER FetchNightly
    Download the latest APK from the GitHub "nightly" release into %USERPROFILE%\Android\apks via gh.

.PARAMETER ReleaseApp
    Target the release package (com.costelinhagames.wreckedmetal) instead of the debug package
    (com.costelinhagames.wreckedmetal.debug). Note: GameActivity is only exported in debug builds;
    for release builds this launches LauncherActivity unless overridden.

.PARAMETER Fidelity
    Launch GameActivity with the "fidelity" profile (dd64-fidelity.ini, N64-faithful settings).

.PARAMETER Env
    Semicolon- or newline-separated DD64_* environment variables written to Data/env.txt for this run.
    Example: "DD64_BOOT=track=4,type=1,car=12;DD64_FAKEGAS=200:1.0;DD64_EXIT_AFTER=600"

.PARAMETER Wait
    Seconds to wait before taking the screenshot and pulling logs (default: 15).
    When DD64_EXIT_AFTER is present in -Env, this acts as the minimum wait before polling for exit
    (or max wait = max(Wait, 90)s).

.PARAMETER KeepCheats
    Do not temporarily disable [cheats] in dd64.ini during the test run.

.PARAMETER NoAlarm
    Suppress the Windows sound notification at the end of the test run.

.PARAMETER OutDir
    Directory where logs and screenshots are saved. Defaults to:
    "$HOME\Documents\WreckedMetal-logs\<yyyyMMdd-HHmmss>"

.EXAMPLE
    .\tools\test-run.ps1 -Install -FetchNightly

.EXAMPLE
    .\tools\test-run.ps1 -Env "DD64_BOOT=track=5,type=1,car=0;DD64_FAKEGAS=180:1.0;DD64_GFXLOG=1" -Wait 25

.EXAMPLE
    .\tools\test-run.ps1 -Fidelity -Env "DD64_BOOT=track=4,type=1;DD64_SHOT=300;DD64_EXIT_AFTER=360" -Wait 20
#>
[CmdletBinding()]
param(
    [switch]$Install,
    [string]$ApkPath,
    [switch]$FetchNightly,
    [switch]$ReleaseApp,
    [switch]$Fidelity,
    [string]$Env,
    [int]$Wait = 15,
    [switch]$KeepCheats,
    [switch]$NoAlarm,
    [string]$OutDir
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Find-Adb {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    $candidates = @(
        "$HOME\Android\platform-tools\adb.exe",
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "C:\Users\Admin\Android\platform-tools\adb.exe",
        "C:\Android\platform-tools\adb.exe"
    )
    foreach ($c in $candidates) {
        if ($c -and (Test-Path $c)) {
            return $c
        }
    }
    throw "adb.exe not found. Install Android platform-tools to '$HOME\Android\platform-tools' and add it to PATH."
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory)][string]$Adb,
        [Parameter(ValueFromRemainingArguments)][string[]]$Args,
        [switch]$AllowFailure
    )
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $Adb @Args 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prevEap
    }
    $text = ($out | ForEach-Object { $_.ToString() }) -join "`n"
    if (-not $AllowFailure -and $code -ne 0) {
        throw "adb $($Args -join ' ') failed (exit $code): $text"
    }
    return [pscustomobject]@{
        ExitCode = $code
        Output   = $text.Trim()
    }
}

function Resolve-Apk {
    param(
        [string]$ExplicitPath,
        [switch]$ForceFetch,
        [switch]$IsRelease
    )
    $repoRoot = Split-Path -Parent $PSScriptRoot
    $apksDir = "$HOME\Android\apks"
    if (-not (Test-Path $apksDir)) {
        New-Item -ItemType Directory -Path $apksDir -Force | Out-Null
    }

    if ($ExplicitPath) {
        if (-not (Test-Path $ExplicitPath)) {
            throw "APK not found at '$ExplicitPath'."
        }
        return (Resolve-Path $ExplicitPath).Path
    }

    $pattern = if ($IsRelease) { "wreckedmetal-armeabi-v7a.apk" } else { "wreckedmetal-armeabi-v7a-debug.apk" }

    if ($ForceFetch) {
        $gh = Get-Command gh -ErrorAction SilentlyContinue
        if (-not $gh) {
            throw "GitHub CLI (gh) is required for -FetchNightly."
        }
        Write-Host "Downloading $pattern from nightly release..." -ForegroundColor Cyan
        & gh release download nightly --repo dedecidao/Wrecked-Metal --pattern $pattern --dir $apksDir --clobber
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to download $pattern from nightly release."
        }
        return (Join-Path $apksDir $pattern)
    }

    $localSubdir = if ($IsRelease) { "app\build\outputs\apk\release\*.apk" } else { "app\build\outputs\apk\debug\*.apk" }
    $localGlob = Join-Path $repoRoot $localSubdir
    $localApks = Get-ChildItem -Path $localGlob -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending
    if ($localApks) {
        return $localApks[0].FullName
    }

    $cached = Join-Path $apksDir $pattern
    if (Test-Path $cached) {
        return $cached
    }

    throw "No local APK found in '$localGlob' or '$cached'. Pass -ApkPath <file> or -FetchNightly."
}

function Disable-IniCheats {
    param([string]$Text)

    $cheatKeys = @('all_cars', 'all_tracks', 'invincible', 'no_time_limit', 'super_ram', 'heavy_car')
    $lines = $Text -split "`r?`n"
    $inCheats = $false
    $out = New-Object System.Collections.Generic.List[string]

    foreach ($line in $lines) {
        $trim = $line.Trim()
        if ($trim -match '^\[(.+)\]$') {
            $inCheats = ($Matches[1].Trim().ToLowerInvariant() -eq 'cheats')
            $out.Add($line)
            continue
        }
        if ($inCheats -and $trim -match '^([A-Za-z0-9_]+)\s*=') {
            $k = $Matches[1].ToLowerInvariant()
            if ($cheatKeys -contains $k) {
                $out.Add("$($Matches[1]) = 0")
                continue
            }
        }
        $out.Add($line)
    }

    return ($out -join "`n") + "`n"
}

function Play-FinishAlarm {
    try {
        [System.Media.SystemSounds]::Exclamation.Play()
        Start-Sleep -Milliseconds 350
        [console]::beep(880, 180)
        [console]::beep(1175, 250)
    } catch {
        # Ignore audio failures in headless/remote sessions
    }
}

$adb = Find-Adb
Write-Host "Using ADB: $adb" -ForegroundColor DarkGray

# Disable mDNS discovery so cold ADB startup doesn't trigger firewall prompts or stall
$env:ADB_MDNS = "0"

# Start ADB server completely detached from PowerShell pipes/job objects if not already listening
if (-not (netstat -ano 2>$null | Select-String "127\.0\.0\.1:5037\s+.*LISTENING")) {
    $cmdLine = "cmd.exe /c `"set ADB_MDNS=0 && `"$adb`" start-server`""
    Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $cmdLine } | Out-Null
    for ($i = 0; $i -lt 20; $i++) {
        if (netstat -ano 2>$null | Select-String "127\.0\.0\.1:5037\s+.*LISTENING") { break }
        Start-Sleep -Milliseconds 200
    }
}

# Verify connected device (wait briefly if transitioning through authorizing)
$devicesOut = ""
$deviceLines = @()
for ($i = 0; $i -lt 10; $i++) {
    $devicesOut = (Invoke-Adb $adb devices).Output
    $deviceLines = @($devicesOut -split "`r?`n" | Where-Object { $_ -match '^\S+\s+device$' })
    if ($deviceLines.Count -gt 0) { break }
    if ($devicesOut -match 'authorizing') {
        Start-Sleep -Milliseconds 500
    } else {
        break
    }
}
if ($deviceLines.Count -eq 0) {
    throw "No authorized Android device connected.`n`nadb devices output:`n$devicesOut`n`nTip: Check USB debugging on the S23 Ultra or switch to a rear motherboard USB port if adb reports offline."
}
if ($deviceLines.Count -gt 1) {
    Write-Warning "Multiple devices connected; ADB commands may fail unless ANDROID_SERIAL is set."
}

$pkg = if ($ReleaseApp) { "com.costelinhagames.wreckedmetal" } else { "com.costelinhagames.wreckedmetal.debug" }
$extFiles = "/sdcard/Android/data/$pkg/files"
$extData = "$extFiles/Data"
$iniName = if ($Fidelity) { "dd64-fidelity.ini" } else { "dd64.ini" }
$remoteIni = "$extFiles/$iniName"
$remoteEnv = "$extData/env.txt"
$remoteLog = "$extData/Logs/dd64.log"

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
if (-not $OutDir) {
    $OutDir = Join-Path "$HOME\Documents\WreckedMetal-logs" $timestamp
}
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
Write-Host "Logs/artifacts dir: $OutDir" -ForegroundColor Cyan

# Step 1: Download / Install APK if requested
if ($FetchNightly -and -not $Install) {
    $downloaded = Resolve-Apk -ExplicitPath $ApkPath -ForceFetch -IsRelease:$ReleaseApp
    Write-Host "Downloaded APK ready at: $downloaded (pass -Install to deploy it)" -ForegroundColor Green
}

if ($Install) {
    $resolvedApk = Resolve-Apk -ExplicitPath $ApkPath -ForceFetch:$FetchNightly -IsRelease:$ReleaseApp
    Write-Host "Installing APK: $resolvedApk" -ForegroundColor Cyan
    & $adb install -r -d $resolvedApk
    if ($LASTEXITCODE -ne 0) {
        throw "APK installation failed."
    }
}

# Check if app Data/ exists (NEVER create files/Data from adb shell before app first run!)
$dataCheck = Invoke-Adb $adb shell "if [ -d '$extData' ]; then echo EXISTS; else echo MISSING; fi" -AllowFailure
$dataExists = ($dataCheck.Output -match 'EXISTS')

$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
$backedUpIniLocal = $null
$pushedEnv = $false

try {
    # Step 2: Backup & clean [cheats] in dd64.ini
    if (-not $KeepCheats -and $dataExists) {
        $iniCheck = Invoke-Adb $adb shell "if [ -f '$remoteIni' ]; then echo EXISTS; fi" -AllowFailure
        if ($iniCheck.Output -match 'EXISTS') {
            $backedUpIniLocal = Join-Path $OutDir "backup-$iniName"
            Invoke-Adb $adb pull $remoteIni $backedUpIniLocal | Out-Null
            $origText = [System.IO.File]::ReadAllText($backedUpIniLocal)
            $cleanText = Disable-IniCheats -Text $origText
            if ($cleanText -ne $origText) {
                $cleanIniLocal = Join-Path $OutDir "clean-$iniName"
                [System.IO.File]::WriteAllText($cleanIniLocal, $cleanText, $utf8NoBom)
                Invoke-Adb $adb push $cleanIniLocal $remoteIni | Out-Null
                Write-Host "Temporarily disabled [cheats] in $iniName (will restore on exit)." -ForegroundColor Yellow
            } else {
                $backedUpIniLocal = $null
            }
        }
    }

    # Step 3: Write Data/env.txt if -Env was provided
    if ($Env) {
        if (-not $dataExists) {
            throw "App data directory '$extData' does not exist yet. Open Wrecked Metal on the device once and pick your ROM first (do NOT create files/Data via adb, or Android permissions/ownership will break)."
        }
        $envLines = ($Env -split ';|\r?\n') | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' }
        $localEnvFile = Join-Path $OutDir "env.txt"
        [System.IO.File]::WriteAllText($localEnvFile, (($envLines -join "`n") + "`n"), $utf8NoBom)
        Invoke-Adb $adb push $localEnvFile $remoteEnv | Out-Null
        $pushedEnv = $true
        Write-Host "Pushed Data/env.txt:" -ForegroundColor Cyan
        $envLines | ForEach-Object { Write-Host "  $_" -ForegroundColor Gray }
    }

    # Step 4: Clear logcat & stop existing process
    Invoke-Adb $adb logcat -c | Out-Null
    Invoke-Adb $adb shell am force-stop $pkg -AllowFailure | Out-Null

    # Step 5: Launch activity
    if ($ReleaseApp) {
        Write-Warning "GameActivity is not exported in release builds; launching LauncherActivity."
        Invoke-Adb $adb shell am start -n "$pkg/io.github.dedecidao.dd64.LauncherActivity" | Out-Null
    } else {
        $startArgs = @("shell", "am", "start", "-n", "$pkg/io.github.dedecidao.dd64.GameActivity")
        if ($Fidelity) {
            $startArgs += @("--es", "profile", "fidelity")
        }
        Write-Host "Launching GameActivity ($pkg)..." -ForegroundColor Green
        Invoke-Adb $adb @startArgs | Out-Null
    }

    # Step 6: Wait (or wait for DD64_EXIT_AFTER) and take screenshot
    $hasExitAfter = ($Env -match 'DD64_EXIT_AFTER=')
    $screenCaptured = $false

    if ($hasExitAfter) {
        $maxWait = [Math]::Max($Wait, 90)
        Write-Host "Waiting for DD64_EXIT_AFTER (timeout ${maxWait}s)..." -ForegroundColor Cyan
        $elapsed = 0
        $sawProcess = $false
        while ($elapsed -lt $maxWait) {
            Start-Sleep -Seconds 1
            $elapsed++
            $pidRes = Invoke-Adb $adb shell "pidof ${pkg}:game" -AllowFailure
            if ($pidRes.Output) {
                $sawProcess = $true
                if (-not $screenCaptured -and $elapsed -ge [Math]::Min(6, $Wait)) {
                    $sc = Invoke-Adb $adb shell screencap -p /sdcard/Download/wm_screen.png -AllowFailure
                    if ($sc.ExitCode -eq 0) {
                        Invoke-Adb $adb pull /sdcard/Download/wm_screen.png (Join-Path $OutDir "screen.png") -AllowFailure | Out-Null
                        Invoke-Adb $adb shell rm -f /sdcard/Download/wm_screen.png -AllowFailure | Out-Null
                        $screenCaptured = $true
                    }
                }
            } elseif ($sawProcess) {
                Write-Host "Game process exited cleanly after ${elapsed}s." -ForegroundColor Green
                break
            }
        }
    } else {
        Write-Host "Waiting ${Wait}s while the game runs..." -ForegroundColor Cyan
        Start-Sleep -Seconds $Wait
    }

    if (-not $screenCaptured) {
        $sc = Invoke-Adb $adb shell screencap -p /sdcard/Download/wm_screen.png -AllowFailure
        if ($sc.ExitCode -eq 0) {
            Invoke-Adb $adb pull /sdcard/Download/wm_screen.png (Join-Path $OutDir "screen.png") -AllowFailure | Out-Null
            Invoke-Adb $adb shell rm -f /sdcard/Download/wm_screen.png -AllowFailure | Out-Null
            $screenCaptured = $true
        }
    }

    # Step 7: Pull logs & any DD64_SHOT files (from internal files/ via run-as, or external)
    $logCheck = Invoke-Adb $adb shell "if [ -f '$remoteLog' ]; then echo EXISTS; fi" -AllowFailure
    if ($logCheck.Output -match 'EXISTS') {
        Invoke-Adb $adb pull $remoteLog (Join-Path $OutDir "dd64.log") | Out-Null
    } else {
        Write-Warning "dd64.log not found at $remoteLog (first boot without ROM?)."
    }

    $internalShots = Invoke-Adb $adb shell "run-as $pkg ls files 2>/dev/null" -AllowFailure
    if ($internalShots.Output) {
        $shotNames = $internalShots.Output -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ -match '^shot_.*\.bmp$' }
        if ($shotNames) {
            try { Add-Type -AssemblyName System.Drawing -ErrorAction SilentlyContinue } catch {}
            foreach ($leaf in $shotNames) {
                $bmpOut = Join-Path $OutDir $leaf
                cmd.exe /c "`"$adb`" exec-out run-as $pkg cat `"files/$leaf`" > `"$bmpOut`"" 2>$null
                if (Test-Path $bmpOut) {
                    Invoke-Adb $adb shell "run-as $pkg rm -f files/$leaf" -AllowFailure | Out-Null
                    try {
                        $pngOut = [System.IO.Path]::ChangeExtension($bmpOut, ".png")
                        $bmp = [System.Drawing.Bitmap]::FromFile($bmpOut)
                        $bmp.Save($pngOut, [System.Drawing.Imaging.ImageFormat]::Png)
                        $bmp.Dispose()
                    } catch {}
                }
            }
        }
    }

    $shotsRes = Invoke-Adb $adb shell "ls '$extFiles'/shot_*.bmp 2>/dev/null" -AllowFailure
    if ($shotsRes.Output) {
        $shotFiles = $shotsRes.Output -split "`r?`n" | Where-Object { $_ -match 'shot_.*\.bmp$' }
        foreach ($sf in $shotFiles) {
            $leaf = Split-Path -Leaf $sf.Trim()
            Invoke-Adb $adb pull $sf.Trim() (Join-Path $OutDir $leaf) -AllowFailure | Out-Null
        }
    }

    $logcatPath = Join-Path $OutDir "logcat.txt"
    cmd.exe /c "`"$adb`" logcat -d -v threadtime -s DD64:V SDL:V AndroidRuntime:E DEBUG:F libc:F > `"$logcatPath`"" 2>$null

} finally {
    # Step 8: Always clean up env.txt and restore dd64.ini
    if ($pushedEnv) {
        Invoke-Adb $adb shell rm -f $remoteEnv -AllowFailure | Out-Null
        Write-Host "Removed temporary Data/env.txt from device." -ForegroundColor DarkGray
    }
    if ($backedUpIniLocal -and (Test-Path $backedUpIniLocal)) {
        Invoke-Adb $adb push $backedUpIniLocal $remoteIni -AllowFailure | Out-Null
        Write-Host "Restored original $iniName on device." -ForegroundColor DarkGray
    }
}

# Step 9: Print summary
Write-Host ""
Write-Host "=== Test Run Summary ===" -ForegroundColor Cyan
Write-Host "Artifacts saved to: $OutDir"
$dd64LogPath = Join-Path $OutDir "dd64.log"
if (Test-Path $dd64LogPath) {
    $highlights = Select-String -Path $dd64LogPath -Pattern "device:|app:|GPU|Vulkan|OpenGL|fps|crash:|shadow:|fatal:"
    if ($highlights) {
        Write-Host "--- dd64.log highlights ---" -ForegroundColor Yellow
        $highlights | Select-Object -First 30 | ForEach-Object { Write-Host "  $($_.Line)" }
    }
}

if (-not $NoAlarm) {
    Play-FinishAlarm
}
