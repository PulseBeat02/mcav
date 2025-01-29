try {
    wsl --status | Out-Null
    Write-Host "WSL is available, proceeding with build..." -ForegroundColor Green
}
catch {
    Write-Host "Windows Subsystem for Linux (WSL) is required but not found or enabled." -ForegroundColor Red
    exit 1
}

$sourcePath = Join-Path $PSScriptRoot "dummy.go"
$wslSourcePath = "/tmp/dummy.go"
$outputDir = Join-Path $PSScriptRoot "output"
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
Write-Host "Output will be saved to: $outputDir" -ForegroundColor Green

if (-not (Test-Path $sourcePath)) {
    Write-Host "Source file 'dummy.go' not found in the current directory." -ForegroundColor Red
    exit 1
}

Write-Host "Setting up build environment in WSL..." -ForegroundColor Yellow

wsl -d Ubuntu -- sudo apt-get update
wsl -d Ubuntu -- sudo apt-get install -y build-essential wget

Write-Host "Installing architecture-specific dependencies..." -ForegroundColor Yellow
wsl -d Ubuntu -- sudo dpkg --add-architecture i386
wsl -d Ubuntu -- sudo dpkg --add-architecture armhf
wsl -d Ubuntu -- sudo dpkg --add-architecture arm64
wsl -d Ubuntu -- sudo apt-get update

wsl -d Ubuntu -- sudo apt-get install -y gcc-multilib g++-multilib
wsl -d Ubuntu -- sudo apt-get install -y linux-libc-dev linux-libc-dev:i386
wsl -d Ubuntu -- sudo apt-get install -y libc6-dev libc6-dev-i386 libc6-dev-amd64
wsl -d Ubuntu -- sudo apt-get install -y gcc-aarch64-linux-gnu libc6-dev-arm64-cross
wsl -d Ubuntu -- sudo apt-get install -y gcc-arm-linux-gnueabi libc6-dev-armel-cross

wsl -d Ubuntu -- bash -c "if [ ! -f /usr/include/asm/errno.h ]; then sudo mkdir -p /usr/include/asm && sudo ln -s /usr/include/asm-generic/errno.h /usr/include/asm/errno.h; fi"
wsl -d Ubuntu -- bash -c "if [ ! -f /usr/include/asm/errno-base.h ]; then sudo ln -s /usr/include/asm-generic/errno-base.h /usr/include/asm/errno-base.h; fi"

$goInstalled = wsl -d Ubuntu -- bash -c "command -v go || echo 'not found'"
if ($goInstalled -match "not found") {
    Write-Host "Installing Go in WSL..." -ForegroundColor Yellow
    wsl -d Ubuntu -- wget https://go.dev/dl/go1.20.4.linux-amd64.tar.gz
    wsl -d Ubuntu -- sudo rm -rf /usr/local/go
    wsl -d Ubuntu -- sudo tar -C /usr/local -xzf go1.20.4.linux-amd64.tar.gz
    wsl -d Ubuntu -- rm go1.20.4.linux-amd64.tar.gz
}

Write-Host "Copying source file to WSL..." -ForegroundColor Yellow
wsl -d Ubuntu -- mkdir -p /tmp
Get-Content $sourcePath | wsl -d Ubuntu -- bash -c "cat > $wslSourcePath"

function Build-Architecture {
    param(
        [string]$Name,
        [string]$Arch,
        [string]$CompilerPrefix = "",
        [string]$ExtraFlags = ""
    )

    Write-Host "Building for $Name Linux..." -ForegroundColor Yellow

    $ccPrefix = ""
    if ($CompilerPrefix) {
        $ccPrefix = "CC=$CompilerPrefix-gcc"
    }

    $buildCmd = "cd /tmp && $ccPrefix CGO_ENABLED=1 GOOS=linux GOARCH=$Arch $ExtraFlags CGO_CFLAGS='-I/usr/include' /usr/local/go/bin/go build -buildmode=c-shared -o libdummy_$Name.so dummy.go"
    $buildResult = wsl -d Ubuntu -- bash -c $buildCmd

    if ($LASTEXITCODE -ne 0) {
        Write-Host "Build failed for $Name" -ForegroundColor Red
        return $false
    }

    $wslPath = ($outputDir -replace ':', '').Replace('\', '/').ToLower()

    $copyCmd = "cp /tmp/libdummy_$Name.so /mnt/$wslPath/"
    $copyResult = wsl -d Ubuntu -- bash -c $copyCmd

    if ($LASTEXITCODE -ne 0) {
        Write-Host "Failed to copy library for $Name" -ForegroundColor Red
        return $false
    }

    return $true
}

$success = $true
$success = $success -and (Build-Architecture -Name "x86_64" -Arch "amd64")
$success = $success -and (Build-Architecture -Name "i386" -Arch "386")
$success = $success -and (Build-Architecture -Name "arm64" -Arch "arm64" -CompilerPrefix "aarch64-linux-gnu")
$success = $success -and (Build-Architecture -Name "arm" -Arch "arm" -CompilerPrefix "arm-linux-gnueabi" -ExtraFlags "GOARM=7")

wsl -d Ubuntu -- rm -f /tmp/dummy.go /tmp/libdummy_*.h

if ($success) {
    Write-Host "Cross-compilation completed successfully!" -ForegroundColor Green
    Write-Host "Libraries saved to: $outputDir" -ForegroundColor Green
} else {
    Write-Host "Cross-compilation failed. Check the errors above." -ForegroundColor Red
    exit 1
}