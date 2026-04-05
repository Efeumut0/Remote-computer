@echo off
if /I not "%~1"=="--run" (
    start "Remote Notification R2 Add-Later Helper" cmd /k call "%~f0" --run
    exit /b
)
shift /1
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul
title Remote Notification R2 Add-Later Helper

set "ROOT=%~dp0"
set "BACKEND_DIR=%ROOT%backend"
set "CONFIG_FILE=%BACKEND_DIR%\wrangler.jsonc"
set "TEMPLATE_FILE_R2=%BACKEND_DIR%\wrangler.template.r2.jsonc"
set "WORKER_URL_FILE=%ROOT%worker-url.txt"
set "TMP_META=%TEMP%\uzb_r2_meta.json"
set "TMP_DEPLOY_OUTPUT=%TEMP%\uzb_r2_deploy_output.txt"

echo.
echo ==========================================
echo   Remote Notification R2 Add-Later Helper
echo ==========================================
echo.

if not exist "%CONFIG_FILE%" (
    echo [ERROR] wrangler.jsonc was not found.
    echo Run backend-setup-helper.bat first.
    pause
    exit /b 1
)

if not exist "%TEMPLATE_FILE_R2%" (
    echo [ERROR] wrangler.template.r2.jsonc was not found.
    pause
    exit /b 1
)

where node >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Node.js was not found.
    pause
    exit /b 1
)

where npm >nul 2>&1
if errorlevel 1 (
    echo [ERROR] npm was not found.
    pause
    exit /b 1
)

pushd "%BACKEND_DIR%"

echo [1/5] Checking packages...
call npm install
if errorlevel 1 goto :fail

echo.
echo [2/5] Checking the Cloudflare account...
call npx wrangler whoami
if errorlevel 1 goto :fail

echo.
echo The R2 bucket name is the name that will appear in the Cloudflare R2 list.
set "BUCKET_NAME=remote-notification-files"
set /p BUCKET_NAME="R2 bucket name [default: remote-notification-files]: "
if "%BUCKET_NAME%"=="" set "BUCKET_NAME=remote-notification-files"
set "BUCKET_NAME=%BUCKET_NAME:"=%"

echo.
echo [3/5] Creating the R2 bucket...
call npx wrangler r2 bucket create "%BUCKET_NAME%"
if errorlevel 1 goto :fail

echo.
echo [4/5] Updating wrangler.jsonc with R2 support...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$config = Get-Content -Raw '%CONFIG_FILE%' | ConvertFrom-Json;" ^
    "$meta = [pscustomobject]@{ WorkerName = $config.name; DatabaseName = $config.d1_databases[0].database_name; DatabaseId = $config.d1_databases[0].database_id };" ^
    "$utf8NoBom = New-Object System.Text.UTF8Encoding($false);" ^
    "[System.IO.File]::WriteAllText('%TMP_META%', ($meta | ConvertTo-Json), $utf8NoBom)"
if errorlevel 1 goto :fail

for /f "usebackq delims=" %%I in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "(Get-Content -Raw '%TMP_META%' | ConvertFrom-Json).WorkerName"`) do set "WORKER_NAME=%%I"
for /f "usebackq delims=" %%I in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "(Get-Content -Raw '%TMP_META%' | ConvertFrom-Json).DatabaseName"`) do set "DB_NAME=%%I"
for /f "usebackq delims=" %%I in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "(Get-Content -Raw '%TMP_META%' | ConvertFrom-Json).DatabaseId"`) do set "DATABASE_ID=%%I"

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$content = Get-Content -Raw '%TEMPLATE_FILE_R2%';" ^
    "$content = $content.Replace('__WORKER_NAME__', '%WORKER_NAME%');" ^
    "$content = $content.Replace('__DATABASE_NAME__', '%DB_NAME%');" ^
    "$content = $content.Replace('__DATABASE_ID__', '%DATABASE_ID%');" ^
    "$content = $content.Replace('__BUCKET_NAME__', '%BUCKET_NAME%');" ^
    "$utf8NoBom = New-Object System.Text.UTF8Encoding($false);" ^
    "[System.IO.File]::WriteAllText('%CONFIG_FILE%', $content, $utf8NoBom)"
if errorlevel 1 goto :fail

del "%TMP_META%" >nul 2>&1

echo.
echo [5/5] Redeploying the Worker...
call npx wrangler deploy > "%TMP_DEPLOY_OUTPUT%" 2>&1
set "DEPLOY_EXIT=%ERRORLEVEL%"
type "%TMP_DEPLOY_OUTPUT%"
if not "%DEPLOY_EXIT%"=="0" goto :fail

call :parse_worker_url "%TMP_DEPLOY_OUTPUT%"
set "WORKER_URL=!PARSED_WORKER_URL!"
if "!WORKER_URL!"=="" if exist "%WORKER_URL_FILE%" (
    set /p WORKER_URL=<"%WORKER_URL_FILE%"
)
if "!WORKER_URL!"=="" (
    echo [WARNING] The Worker URL could not be detected automatically from deploy output.
    set /p WORKER_URL="Paste the exact full https://...workers.dev address shown by deploy: "
    set "WORKER_URL=!WORKER_URL:"=!"
)
if "!WORKER_URL!"=="" goto :fail

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$utf8NoBom = New-Object System.Text.UTF8Encoding($false);" ^
    "[System.IO.File]::WriteAllText('%WORKER_URL_FILE%', '!WORKER_URL!', $utf8NoBom)"
if errorlevel 1 goto :fail

echo.
echo R2 was added successfully.
echo You do not need to build a new APK.
echo The same Worker URL will continue to work with R2 enabled.
echo.
pause
goto :cleanup_success

:parse_worker_url
set "PARSED_WORKER_URL="
for /f "usebackq delims=" %%I in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "$text = Get-Content -Raw '%~1'; $match = [regex]::Match($text, 'https://[a-z0-9-]+(?:\.[a-z0-9-]+)*\.workers\.dev'); if ($match.Success) { Write-Output $match.Value }"`) do set "PARSED_WORKER_URL=%%I"
exit /b 0

:cleanup_success
del "%TMP_META%" >nul 2>&1
del "%TMP_DEPLOY_OUTPUT%" >nul 2>&1
popd
exit /b 0

:fail
del "%TMP_META%" >nul 2>&1
del "%TMP_DEPLOY_OUTPUT%" >nul 2>&1
echo.
echo [ERROR] The R2 add-later flow could not be completed.
echo Read the last error above, fix it, and try again.
echo.
pause
popd
exit /b 1

