@echo off
echo Publishing artifacts...

call gradlew --parallel mcav-common:publish mcav-bukkit:publish mcav-installer:publish mcav-jda:publish mcav-http:publish

if %ERRORLEVEL% neq 0 (
    echo Failed to publish artifacts
    exit /b %ERRORLEVEL%
)

echo All artifacts published successfully