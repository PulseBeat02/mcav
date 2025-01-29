@echo off
echo Publishing artifacts...

call gradlew --parallel mcav-common:publish mcav-bukkit:publish mcav-installer:publish

if %ERRORLEVEL% neq 0 (
    echo Failed to publish artifacts
    exit /b %ERRORLEVEL%
)

echo All artifacts published successfully