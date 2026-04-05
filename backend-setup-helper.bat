@echo off
if /I not "%~1"=="--run" (
    start "Remote Notification Backend Setup Helper" cmd /k call "%~f0" --run
    exit /b
)
shift /1
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul
title Remote Notification Backend Setup Helper

set "ROOT=%~dp0"
set "BACKEND_DIR=%ROOT%backend"
set "TEMPLATE_FILE=%BACKEND_DIR%\wrangler.template.jsonc"
set "TEMPLATE_FILE_R2=%BACKEND_DIR%\wrangler.template.r2.jsonc"
set "CONFIG_FILE=%BACKEND_DIR%\wrangler.jsonc"
set "WORKER_URL_FILE=%ROOT%worker-url.txt"
set "TMP_D1_OUTPUT=%TEMP%\uzb_d1_create_output.txt"
set "TMP_DEPLOY_OUTPUT=%TEMP%\uzb_deploy_output.txt"
set "TMP_PROJECT_ID=%TEMP%\uzb_firebase_project_id.txt"
set "TMP_CLIENT_EMAIL=%TEMP%\uzb_firebase_client_email.txt"
set "TMP_PRIVATE_KEY=%TEMP%\uzb_firebase_private_key.txt"

echo.
echo ==========================================
echo   Remote Notification Backend Setup Helper
echo ==========================================
echo.

if not exist "%BACKEND_DIR%" (
    echo [ERROR] backend folder was not found: %BACKEND_DIR%
    pause
    exit /b 1
)

if not exist "%BACKEND_DIR%\package.json" (
    echo [ERROR] package.json was not found in the backend folder.
    pause
    exit /b 1
)

if not exist "%TEMPLATE_FILE%" (
    echo [ERROR] wrangler.template.jsonc was not found.
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
    echo Install Node.js LTS first, then run this file again.
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

echo [1/9] Checking Node and npm versions...
node -v
if errorlevel 1 goto :fail
call npm -v
if errorlevel 1 goto :fail

echo.
echo [2/9] Installing npm packages...
call npm install
if errorlevel 1 goto :fail

echo.
echo [3/9] Starting Cloudflare Wrangler login...
echo Approve the login in the browser window.
call npx wrangler login
if errorlevel 1 goto :fail

echo.
echo [4/9] Checking the active Cloudflare account...
call npx wrangler whoami
if errorlevel 1 goto :fail

echo.
echo You will now enter the Worker and D1 names.
echo - Worker name: the first part of the final workers.dev address
echo - D1 name: the name shown in the Cloudflare D1 list
echo Examples: my-remote-control / remote-control-db
echo.
set "WORKER_NAME=remote-notification-worker"
set /p WORKER_NAME="Worker name [default: remote-notification-worker]: "
if "%WORKER_NAME%"=="" set "WORKER_NAME=remote-notification-worker"
set "WORKER_NAME=%WORKER_NAME:"=%"

set "DB_NAME=remote-notification-db"
set /p DB_NAME="D1 database name [default: remote-notification-db]: "
if "%DB_NAME%"=="" set "DB_NAME=remote-notification-db"
set "DB_NAME=%DB_NAME:"=%"

set "ENABLE_R2=N"
echo.
echo R2 is optional.
echo If R2 is disabled on your account or you do not want it right now, choose N and continue.
set /p ENABLE_R2="Do you want to enable R2 now? [Y/N, default: N]: "
if /I "%ENABLE_R2%"=="" set "ENABLE_R2=N"
if /I "%ENABLE_R2%"=="Y" (
    set "BUCKET_NAME=remote-notification-files"
    set /p BUCKET_NAME="R2 bucket name [default: remote-notification-files]: "
    if "!BUCKET_NAME!"=="" set "BUCKET_NAME=remote-notification-files"
    set "BUCKET_NAME=!BUCKET_NAME:"=!"
)

echo.
echo [5/9] Creating the D1 database...
call npx wrangler d1 create "%DB_NAME%" > "%TMP_D1_OUTPUT%" 2>&1
set "D1_CREATE_EXIT=%ERRORLEVEL%"
type "%TMP_D1_OUTPUT%"
if not "%D1_CREATE_EXIT%"=="0" goto :fail

call :parse_database_id "%TMP_D1_OUTPUT%"
if not "!DATABASE_ID!"=="" (
    echo [INFO] database_id was detected automatically: !DATABASE_ID!
) else (
    echo.
    echo [WARNING] database_id could not be detected automatically.
    set /p DATABASE_ID="Paste the database_id value from the output above: "
    set "DATABASE_ID=!DATABASE_ID:"=!"
)

if "!DATABASE_ID!"=="" (
    echo [ERROR] database_id cannot be empty.
    goto :fail
)

set "CONFIG_SOURCE=%TEMPLATE_FILE%"
if /I "%ENABLE_R2%"=="Y" (
    echo.
    echo [6/9] Creating the R2 bucket...
    call npx wrangler r2 bucket create "%BUCKET_NAME%"
    if errorlevel 1 (
        echo.
        echo [WARNING] The R2 bucket could not be created.
        echo You can continue without R2 and add it later with add-r2-later-helper.bat.
        set "CONTINUE_WITHOUT_R2=Y"
        set /p CONTINUE_WITHOUT_R2="Continue without R2? [Y/N, default: Y]: "
        if /I "!CONTINUE_WITHOUT_R2!"=="" set "CONTINUE_WITHOUT_R2=Y"
        if /I not "!CONTINUE_WITHOUT_R2!"=="Y" goto :fail
        set "ENABLE_R2=N"
        set "BUCKET_NAME="
    )
)

if /I "%ENABLE_R2%"=="Y" (
    set "CONFIG_SOURCE=%TEMPLATE_FILE_R2%"
) else (
    echo.
    echo [6/9] R2 was skipped in this setup.
    echo The base system and small-file mode will still work.
)

echo.
echo Creating wrangler.jsonc from the template...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$content = Get-Content -Raw '%CONFIG_SOURCE%';" ^
    "$content = $content.Replace('__WORKER_NAME__', '%WORKER_NAME%');" ^
    "$content = $content.Replace('__DATABASE_NAME__', '%DB_NAME%');" ^
    "$content = $content.Replace('__DATABASE_ID__', '!DATABASE_ID!');" ^
    "$content = $content.Replace('__BUCKET_NAME__', '!BUCKET_NAME!');" ^
    "$utf8NoBom = New-Object System.Text.UTF8Encoding($false);" ^
    "[System.IO.File]::WriteAllText('%CONFIG_FILE%', $content, $utf8NoBom)"
if errorlevel 1 goto :fail

echo.
echo [7/9] Applying D1 migrations...
call npm run db:migrate:remote
if errorlevel 1 goto :fail

echo.
echo [8/9] Setting Firebase service account secrets...
set /p SERVICE_ACCOUNT_JSON="Paste the path of the Firebase service account JSON file: "
set "SERVICE_ACCOUNT_JSON=%SERVICE_ACCOUNT_JSON:"=%"
if not exist "%SERVICE_ACCOUNT_JSON%" (
    echo [ERROR] The service account JSON file was not found.
    goto :fail
)

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$json = Get-Content -Raw '%SERVICE_ACCOUNT_JSON%' | ConvertFrom-Json;" ^
    "$utf8NoBom = New-Object System.Text.UTF8Encoding($false);" ^
    "[System.IO.File]::WriteAllText('%TMP_PROJECT_ID%', [string]$json.project_id, $utf8NoBom);" ^
    "[System.IO.File]::WriteAllText('%TMP_CLIENT_EMAIL%', [string]$json.client_email, $utf8NoBom);" ^
    "[System.IO.File]::WriteAllText('%TMP_PRIVATE_KEY%', [string]$json.private_key, $utf8NoBom);"
if errorlevel 1 goto :fail

type "%TMP_PROJECT_ID%" | call npx wrangler secret put FIREBASE_PROJECT_ID
if errorlevel 1 goto :fail
type "%TMP_CLIENT_EMAIL%" | call npx wrangler secret put FIREBASE_CLIENT_EMAIL
if errorlevel 1 goto :fail
type "%TMP_PRIVATE_KEY%" | call npx wrangler secret put FIREBASE_PRIVATE_KEY
if errorlevel 1 goto :fail

del "%TMP_PROJECT_ID%" >nul 2>&1
del "%TMP_CLIENT_EMAIL%" >nul 2>&1
del "%TMP_PRIVATE_KEY%" >nul 2>&1

echo.
echo [9/9] Deploying the Worker...
call npx wrangler deploy > "%TMP_DEPLOY_OUTPUT%" 2>&1
set "DEPLOY_EXIT=%ERRORLEVEL%"
type "%TMP_DEPLOY_OUTPUT%"
if not "%DEPLOY_EXIT%"=="0" goto :fail

call :parse_worker_url "%TMP_DEPLOY_OUTPUT%"
set "WORKER_URL=!PARSED_WORKER_URL!"
if "!WORKER_URL!"=="" (
    echo.
    echo [WARNING] The Worker URL could not be detected automatically from deploy output.
    set /p WORKER_URL="Paste the exact full https://...workers.dev address shown by deploy: "
    set "WORKER_URL=!WORKER_URL:"=!"
)

if "!WORKER_URL!"=="" (
    echo [ERROR] Worker URL cannot be empty.
    goto :fail
)

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$utf8NoBom = New-Object System.Text.UTF8Encoding($false);" ^
    "[System.IO.File]::WriteAllText('%WORKER_URL_FILE%', '!WORKER_URL!', $utf8NoBom)"
if errorlevel 1 goto :fail

call :run_worker_probe "!WORKER_URL!"
if errorlevel 1 goto :fail

echo.
echo Setup completed.
echo Worker URL file created: %WORKER_URL_FILE%
echo Use the same Worker URL in both the Windows agent and the Android app.
if /I "%ENABLE_R2%"=="N" echo If needed, you can add R2 later with add-r2-later-helper.bat.
echo If the agent later shows 1101, run the latest repair-d1-tables-helper.bat from the main folder.
echo.
pause
goto :cleanup_success

:parse_database_id
set "DATABASE_ID="
for /f "usebackq delims=" %%I in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "$text = Get-Content -Raw '%~1'; $patterns = @('(?im)database_id[^0-9a-fA-F-]*([0-9a-fA-F-]{36})', '(?im)database id[^0-9a-fA-F-]*([0-9a-fA-F-]{36})', '(?im)\b([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\b'); foreach ($pattern in $patterns) { $match = [regex]::Match($text, $pattern); if ($match.Success) { $value = if ($match.Groups.Count -gt 1) { $match.Groups[1].Value } else { $match.Value }; Write-Output $value; break } }"`) do set "DATABASE_ID=%%I"
exit /b 0

:parse_worker_url
set "PARSED_WORKER_URL="
for /f "usebackq delims=" %%I in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "$text = Get-Content -Raw '%~1'; $match = [regex]::Match($text, 'https://[a-z0-9-]+(?:\.[a-z0-9-]+)*\.workers\.dev'); if ($match.Success) { Write-Output $match.Value }"`) do set "PARSED_WORKER_URL=%%I"
exit /b 0

:run_worker_probe
echo.
echo [VERIFY] Testing Worker /health and /api/mobile/pair...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$workerUrl = '%~1'.TrimEnd('/');" ^
    "$healthStatus = 0; $healthBody = ''; $healthOk = $false;" ^
    "try { $healthResponse = Invoke-WebRequest -Uri ($workerUrl + '/health') -UseBasicParsing -ErrorAction Stop; $healthStatus = [int]$healthResponse.StatusCode; $healthBody = [string]$healthResponse.Content } catch { if ($_.Exception.Response) { $healthStatus = [int]$_.Exception.Response.StatusCode.value__; $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream()); $healthBody = $reader.ReadToEnd(); $reader.Close() } else { Write-Host ('[ERROR] /health request could not run: ' + $_.Exception.Message); exit 11 } };" ^
    "try { $healthJson = $healthBody | ConvertFrom-Json; if ($null -ne $healthJson.ok) { $healthOk = [bool]$healthJson.ok } } catch {};" ^
    "if ($healthStatus -lt 200 -or $healthStatus -ge 300 -or -not $healthOk) { Write-Host '[ERROR] /health verification failed.'; if ($healthBody) { Write-Host $healthBody }; exit 11 };" ^
    "$pairJson = '{""pairingCode"":""ABCDEF"",""deviceName"":""probe-device"",""fcmToken"":""probe-token""}';" ^
    "$pairStatus = 0; $pairBody = '';" ^
    "try { $pairResponse = Invoke-WebRequest -Uri ($workerUrl + '/api/mobile/pair') -Method Post -Body $pairJson -ContentType 'application/json' -UseBasicParsing -ErrorAction Stop; $pairStatus = [int]$pairResponse.StatusCode; $pairBody = [string]$pairResponse.Content } catch { if ($_.Exception.Response) { $pairStatus = [int]$_.Exception.Response.StatusCode.value__; $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream()); $pairBody = $reader.ReadToEnd(); $reader.Close() } else { Write-Host ('[ERROR] Pair endpoint request could not run: ' + $_.Exception.Message); exit 12 } };" ^
    "if ($pairBody -match 'error\s*code\s*:\s*1101' -or $pairBody -match 'Worker threw exception') { Write-Host '[ERROR] The pair endpoint still returns 1101. The deploy or backend setup may still be broken.'; exit 12 };" ^
    "if ($pairStatus -ge 500) { Write-Host ('[ERROR] The pair endpoint returned unexpected status ' + $pairStatus + '.'); if ($pairBody) { Write-Host $pairBody }; exit 13 };" ^
    "Write-Host ('[OK] Pair endpoint responded normally. HTTP ' + $pairStatus);"
if errorlevel 1 exit /b 1
exit /b 0

:cleanup_success
del "%TMP_D1_OUTPUT%" >nul 2>&1
del "%TMP_DEPLOY_OUTPUT%" >nul 2>&1
popd
exit /b 0

:fail
del "%TMP_PROJECT_ID%" >nul 2>&1
del "%TMP_CLIENT_EMAIL%" >nul 2>&1
del "%TMP_PRIVATE_KEY%" >nul 2>&1
del "%TMP_D1_OUTPUT%" >nul 2>&1
del "%TMP_DEPLOY_OUTPUT%" >nul 2>&1
echo.
echo [ERROR] Setup could not be completed.
echo Read the last error above, fix it, and try again.
echo.
pause
popd
exit /b 1

