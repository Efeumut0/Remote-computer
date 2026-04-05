@echo off
if /I not "%~1"=="--run" (
    start "Remote Notification D1 Repair Helper" cmd /k call "%~f0" --run
    exit /b
)
shift /1
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul
title Remote Notification D1 Repair Helper

set "ROOT=%~dp0"
set "BACKEND_DIR=%ROOT%backend"
set "CONFIG_FILE=%BACKEND_DIR%\wrangler.jsonc"
set "TEMPLATE_FILE=%BACKEND_DIR%\wrangler.template.jsonc"
set "TEMPLATE_FILE_R2=%BACKEND_DIR%\wrangler.template.r2.jsonc"
set "WORKER_URL_FILE=%ROOT%worker-url.txt"
set "TMP_META=%TEMP%\uzb_repair_meta.json"
set "TMP_DEPLOY_OUTPUT=%TEMP%\uzb_repair_deploy_output.txt"

echo.
echo ==========================================
echo   Remote Notification D1 Repair Helper
echo ==========================================
echo.

if not exist "%CONFIG_FILE%" (
    echo [ERROR] wrangler.jsonc was not found.
    echo Run backend-kurulum-yardimcisi.bat first.
    pause
    exit /b 1
)

if not exist "%TEMPLATE_FILE%" (
    echo [ERROR] wrangler.template.jsonc was not found.
    echo Please use the latest setup package.
    pause
    exit /b 1
)

if not exist "%TEMPLATE_FILE_R2%" (
    echo [ERROR] wrangler.template.r2.jsonc was not found.
    echo Please use the latest setup package.
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

echo [1/6] Reading the current Worker configuration...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$config = Get-Content -Raw '%CONFIG_FILE%' | ConvertFrom-Json;" ^
    "$bucketName = '';" ^
    "if ($config.r2_buckets -and $config.r2_buckets.Count -gt 0) { $bucketName = $config.r2_buckets[0].bucket_name };" ^
    "$meta = [pscustomobject]@{ WorkerName = $config.name; DatabaseName = $config.d1_databases[0].database_name; DatabaseId = $config.d1_databases[0].database_id; BucketName = $bucketName };" ^
    "$utf8NoBom = New-Object System.Text.UTF8Encoding($false);" ^
    "[System.IO.File]::WriteAllText('%TMP_META%', ($meta | ConvertTo-Json), $utf8NoBom)"
if errorlevel 1 goto :fail

for /f "usebackq delims=" %%I in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "(Get-Content -Raw '%TMP_META%' | ConvertFrom-Json).WorkerName"`) do set "WORKER_NAME=%%I"
for /f "usebackq delims=" %%I in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "(Get-Content -Raw '%TMP_META%' | ConvertFrom-Json).DatabaseName"`) do set "DB_NAME=%%I"
for /f "usebackq delims=" %%I in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "(Get-Content -Raw '%TMP_META%' | ConvertFrom-Json).DatabaseId"`) do set "DATABASE_ID=%%I"
for /f "usebackq delims=" %%I in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "(Get-Content -Raw '%TMP_META%' | ConvertFrom-Json).BucketName"`) do set "BUCKET_NAME=%%I"

if "%WORKER_NAME%"=="" (
    echo [ERROR] The Worker name could not be read from wrangler.jsonc.
    goto :fail
)
if "%DB_NAME%"=="" (
    echo [ERROR] The D1 database name could not be read from wrangler.jsonc.
    goto :fail
)
if "%DATABASE_ID%"=="" (
    echo [ERROR] The database_id could not be read from wrangler.jsonc.
    goto :fail
)

echo.
echo [2/6] Rebuilding wrangler.jsonc with Durable Object and D1 bindings...
set "CONFIG_SOURCE=%TEMPLATE_FILE%"
if not "%BUCKET_NAME%"=="" set "CONFIG_SOURCE=%TEMPLATE_FILE_R2%"
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$content = Get-Content -Raw '%CONFIG_SOURCE%';" ^
    "$content = $content.Replace('__WORKER_NAME__', '%WORKER_NAME%');" ^
    "$content = $content.Replace('__DATABASE_NAME__', '%DB_NAME%');" ^
    "$content = $content.Replace('__DATABASE_ID__', '%DATABASE_ID%');" ^
    "$content = $content.Replace('__BUCKET_NAME__', '%BUCKET_NAME%');" ^
    "$utf8NoBom = New-Object System.Text.UTF8Encoding($false);" ^
    "[System.IO.File]::WriteAllText('%CONFIG_FILE%', $content, $utf8NoBom)"
if errorlevel 1 goto :fail

del "%TMP_META%" >nul 2>&1

echo.
echo [3/6] Checking packages...
call npm install
if errorlevel 1 goto :fail

echo.
echo [4/6] Checking the Cloudflare account...
call npx wrangler whoami
if errorlevel 1 goto :fail

echo.
echo [5/6] Applying D1 migrations to the remote database...
call npm run db:migrate:remote
if errorlevel 1 goto :fail

echo.
echo [6/6] Redeploying the latest Worker code...
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
if "!WORKER_URL!"=="" (
    echo [ERROR] Worker URL cannot be empty.
    goto :fail
)

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$utf8NoBom = New-Object System.Text.UTF8Encoding($false);" ^
    "[System.IO.File]::WriteAllText('%WORKER_URL_FILE%', '!WORKER_URL!', $utf8NoBom)"
if errorlevel 1 goto :fail

call :run_worker_probe "!WORKER_URL!"
if errorlevel 1 (
    echo.
    echo [WARNING] D1 repair finished, but the pair endpoint still looks broken.
    echo The backend may have been set up with an older package.
    echo The safest fix is to rerun the latest backend-kurulum-yardimcisi.bat from the current package.
    goto :fail
)

echo.
echo The Worker config, D1 schema and Durable Object bindings were repaired.
echo The latest Worker code was redeployed.
echo If the agent showed 1101, click Save / Connect again.
echo You can keep using the same Worker URL; no new APK build is required.
echo.
pause
goto :cleanup_success

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
del "%TMP_META%" >nul 2>&1
del "%TMP_DEPLOY_OUTPUT%" >nul 2>&1
popd
exit /b 0

:fail
del "%TMP_META%" >nul 2>&1
del "%TMP_DEPLOY_OUTPUT%" >nul 2>&1
echo.
echo [ERROR] D1 repair could not be completed.
echo Read the last error above, fix it, and try again.
echo.
pause
popd
exit /b 1
