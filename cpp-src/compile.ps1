# compile.ps1
$dockerInstalled = $false
try {
    $dockerVersion = docker --version
    Write-Host "Docker is already installed: $dockerVersion"
    $dockerInstalled = $true
} catch {
    Write-Host "Docker is not installed. Installing Docker Desktop..."

    $dockerInstallerUrl = "https://desktop.docker.com/win/main/amd64/Docker%20Desktop%20Installer.exe"
    $installerPath = "$env:TEMP\DockerDesktopInstaller.exe"

    Invoke-WebRequest -Uri $dockerInstallerUrl -OutFile $installerPath

    Start-Process -FilePath $installerPath -ArgumentList "install", "--quiet" -Wait

    Write-Host "Docker Desktop has been installed. Please restart your computer to complete installation."
    Write-Host "After restart, run this script again to build the cross-compiled libraries."
    exit 0
}

if ($dockerInstalled) {

    $dockerRunning = $false
    $maxAttempts = 5
    $attempts = 0

    Write-Host "Checking if Docker Engine is running..."

    while (-not $dockerRunning -and $attempts -lt $maxAttempts) {
        try {
            $status = docker ps 2>&1
            if ($LASTEXITCODE -eq 0) {
                $dockerRunning = $true
                Write-Host "Docker Engine is running and responsive."
            } else {
                throw "Docker command failed with exit code $LASTEXITCODE"
            }
        } catch {
            $attempts++
            Write-Host "Docker Engine is not responding (attempt $attempts of $maxAttempts)."

            if ($attempts -lt $maxAttempts) {
                Write-Host "Attempting to start Docker Desktop..."

                try {
                    $dockerProcess = Get-Process "Docker Desktop" -ErrorAction SilentlyContinue
                    if (-not $dockerProcess) {
                        Start-Process -FilePath "C:\Program Files\Docker\Docker\Docker Desktop.exe"
                    } else {
                        Write-Host "Docker Desktop process is already running, waiting for engine to start..."
                    }

                    Write-Host "Waiting for Docker Engine to start (this may take a minute)..."
                    Start-Sleep -Seconds 45
                } catch {
                    Write-Host "Failed to start Docker Desktop. Please start it manually."
                    Write-Host "Error: $_"
                }
            } else {
                Write-Host "Failed to connect to Docker after multiple attempts." -ForegroundColor Red
                Write-Host "Please ensure Docker Desktop is running and try again." -ForegroundColor Red
                exit 1
            }
        }
    }

    if (-not $dockerRunning) {
        Write-Host "Docker Engine is not responding. Please start Docker Desktop manually and try again." -ForegroundColor Red
        exit 1
    }

    New-Item -ItemType Directory -Force -Path ".\output" | Out-Null

    if (-not (Test-Path ".\filterlite.c")) {
        Write-Host "filterlite.c not found in the current directory" -ForegroundColor Red
        exit 1
    }

    if (-not (Test-Path ".\Dockerfile")) {
        Write-Host "Dockerfile not found in the current directory" -ForegroundColor Red
        exit 1
    }

    Write-Host "Building Docker image for cross-compilation with BuildKit..."
    $env:DOCKER_BUILDKIT = "1"
    docker build --progress=plain -t filterlite-builder .

    if ($LASTEXITCODE -eq 0) {
        Write-Host "Running container to extract compiled libraries..."
        docker run --rm -v "${PWD}\output:/mounted-output" filterlite-builder

        if ($LASTEXITCODE -eq 0) {
            Write-Host "Cross-compilation successful! Libraries are available in the output directory:"
            Get-ChildItem ".\output"
        } else {
            Write-Host "Error during container execution." -ForegroundColor Red
        }
    } else {
        Write-Host "Error building Docker image." -ForegroundColor Red
    }
}