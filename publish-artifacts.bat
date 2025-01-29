@echo off
echo Publishing artifacts...

call gradlew --parallel mcav-browser:publish mcav-bukkit:publish mcav-common:publish mcav-installer:publish mcav-jda:publish mcav-vm:publish mcav-vnc:publish mcav-http:publish

if %ERRORLEVEL% neq 0 (
    echo Failed to publish artifacts
    exit /b %ERRORLEVEL%
)

echo All artifacts published successfully