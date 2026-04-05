@echo off
if /I not "%~1"=="--run" (
    start "Remote Notification APK Build Helper" cmd /k call "%~f0" --run
    exit /b
)
shift /1
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul
title Remote Notification APK Build Helper

set "ROOT=%~dp0"
set "APK_DIR=%ROOT%apk"
set "GOOGLE_SERVICES=%APK_DIR%\app\google-services.json"
set "LOCAL_PROPERTIES=%APK_DIR%\local.properties"

echo.
echo ==========================================
echo   Remote Notification APK Build Helper
echo ==========================================
echo.

if not exist "%APK_DIR%" (
    echo [ERROR] apk folder was not found: %APK_DIR%
    pause
    exit /b 1
)

if not exist "%APK_DIR%\gradlew.bat" (
    echo [ERROR] gradlew.bat was not found.
    pause
    exit /b 1
)

where java >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java was not found.
    echo Install JDK 17 or newer first.
    pause
    exit /b 1
)

echo [1/5] Checking Java version...
java -version
if errorlevel 1 (
    echo [ERROR] Java could not be started.
    pause
    exit /b 1
)

for /f "tokens=3" %%I in ('java -version 2^>^&1 ^| findstr /i "version"') do set "JAVA_VERSION_RAW=%%~I"
set "JAVA_MAJOR=%JAVA_VERSION_RAW%"
if /I "%JAVA_MAJOR:~0,2%"=="1." (
    set "JAVA_MAJOR=%JAVA_MAJOR:~2,1%"
) else (
    for /f "tokens=1 delims=." %%I in ("%JAVA_MAJOR%") do set "JAVA_MAJOR=%%I"
)

if %JAVA_MAJOR% LSS 17 (
    echo [ERROR] Java is too old: %JAVA_VERSION_RAW%
    echo This Android project needs JDK 17 or newer.
    pause
    exit /b 1
)

if not exist "%GOOGLE_SERVICES%" (
    echo [ERROR] google-services.json was not found.
    echo Put the file from Firebase here:
    echo %GOOGLE_SERVICES%
    pause
    exit /b 1
)

call :resolve_sdk_path
if errorlevel 1 exit /b 1

if not exist "%APK_DIR%\gradle\wrapper\gradle-wrapper.properties" (
    echo [ERROR] Gradle wrapper files are missing.
    pause
    exit /b 1
)

pushd "%APK_DIR%"

echo [3/5] Checking Android project files...
if not exist "app\build.gradle.kts" (
    echo [ERROR] app\build.gradle.kts was not found.
    goto :fail
)

echo [4/5] Starting debug APK build...
call gradlew.bat :app:assembleDebug
if errorlevel 1 goto :fail

set "APK_OUTPUT=%APK_DIR%\app\build\outputs\apk\debug\app-debug.apk"
echo.
echo [5/5] Build completed.
echo APK output file:
echo %APK_OUTPUT%
echo.
pause
popd
exit /b 0

:resolve_sdk_path
set "SDK_PATH="

if exist "%LOCAL_PROPERTIES%" (
    for /f "usebackq delims=" %%I in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "$file = '%LOCAL_PROPERTIES%'; $line = Get-Content $file -ErrorAction SilentlyContinue | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1; if ($line) { $value = $line.Substring(8); $value = $value -replace '\\\\', '\' -replace '\:', ':'; if (Test-Path $value) { Write-Output $value } }"`) do set "SDK_PATH=%%I"
)

if not "!SDK_PATH!"=="" (
    echo [2/5] Using Android SDK path from local.properties:
    echo !SDK_PATH!
    exit /b 0
)

for /f "usebackq delims=" %%I in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "$candidates = @(); $candidates += $env:ANDROID_SDK_ROOT; $candidates += $env:ANDROID_HOME; $candidates += Join-Path $env:LOCALAPPDATA 'Android\Sdk'; $candidates += Join-Path $env:USERPROFILE 'AppData\Local\Android\Sdk'; $regPaths = @('HKCU:\Software\Google\AndroidStudio*', 'HKLM:\Software\Android Studio', 'HKCU:\Software\Android Studio'); foreach ($regPath in $regPaths) { Get-ItemProperty -Path $regPath -ErrorAction SilentlyContinue | ForEach-Object { if ($_.SdkPath) { $candidates += $_.SdkPath } } }; $sdk = $candidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1; if ($sdk) { Write-Output $sdk }"`) do set "SDK_PATH=%%I"

if not "!SDK_PATH!"=="" (
    echo [2/5] Android SDK path was found automatically:
    echo !SDK_PATH!
    call :write_local_properties
    exit /b 0
)

echo [2/5] Android SDK path could not be found automatically.
echo The Android SDK usually comes with Android Studio.
echo Check one of these places in Android Studio:
echo - More Actions ^> SDK Manager
echo - or Settings ^> Android SDK
echo Copy the Android SDK Location value and paste it here.
echo Typical default path:
echo %LOCALAPPDATA%\Android\Sdk
echo.

:ask_sdk_path
set "SDK_PATH="
set /p SDK_PATH="Paste the Android SDK folder path (or type EXIT): "
if /I "!SDK_PATH!"=="EXIT" (
    echo [ERROR] The operation was cancelled because the SDK path was not provided.
    pause
    exit /b 1
)
set "SDK_PATH=!SDK_PATH:"=!"
if "!SDK_PATH!"=="" (
    echo [ERROR] The Android SDK path cannot be empty.
    goto :ask_sdk_path
)

if not exist "!SDK_PATH!" (
    echo [ERROR] The Android SDK folder was not found:
    echo !SDK_PATH!
    echo Check the Android SDK Location inside Android Studio and try again.
    echo.
    goto :ask_sdk_path
)

call :write_local_properties
exit /b 0

:write_local_properties
echo [2/5] Updating local.properties...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$sdk = '!SDK_PATH!' -replace '\\','/';" ^
    "[System.IO.File]::WriteAllText('%LOCAL_PROPERTIES%', ('sdk.dir=' + $sdk), [System.Text.Encoding]::ASCII)"
if errorlevel 1 (
    echo [ERROR] local.properties could not be created or updated.
    pause
    exit /b 1
)
exit /b 0

:fail
echo.
echo [ERROR] APK build could not be completed.
echo Common reasons:
echo - missing google-services.json
echo - missing Android SDK path
echo - missing Java/JDK
echo - missing Gradle or project files
echo.
pause
popd
exit /b 1
