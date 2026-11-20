@echo off
setlocal EnableExtensions DisableDelayedExpansion
if /i "%~1"=="--help" goto help
if /i "%~1"=="/?" goto help
set "APK=%~dp0PerfMon-Plus.apk"
set "SERIAL=%~2"
if not "%~3"=="" goto usage_error
if /i "%~1"=="--list" goto find_adb
if /i "%~1"=="--build" goto build
if not "%~1"=="" set "APK=%~f1"
if not "%~1"=="" goto check_apk
if not exist "%APK%" set "APK=%~dp0app\build\outputs\apk\release\app-release.apk"
goto check_apk

:build
if not exist "%~dp0gradlew.bat" (
  echo Kaynak ZIP dosyasini acip betigi proje klasorunden calistirin.
  exit /b 2
)
if not defined PERFMON_KEYSTORE goto signing
if not defined KEYSTORE_PASSWORD goto signing
if not defined KEY_ALIAS goto signing
if not defined KEY_PASSWORD goto signing
pushd "%~dp0" || exit /b 1
call gradlew.bat :app:testDebugUnitTest :app:assembleRelease
if errorlevel 1 (
  popd
  exit /b 1
)
popd
set "APK=%~dp0app\build\outputs\apk\release\app-release.apk"

:check_apk
if not exist "%APK%" (
  echo Imzali APK bulunamadi. APK yolunu verin veya kaynak klasorunde --build kullanin.
  exit /b 2
)
for %%F in ("%APK%") do if /i not "%%~xF"==".apk" goto usage_error

:find_adb
set "ADB="
for /f "delims=" %%A in ('where adb.exe 2^>nul') do if not defined ADB set "ADB=%%A"
if not defined ADB if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
if not defined ADB if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not defined ADB (
  echo adb.exe bulunamadi. Android SDK Platform-Tools klasorunu PATH'e ekleyin.
  exit /b 2
)
if /i "%~1"=="--list" goto list
if defined SERIAL goto check_device
set /a COUNT=0 >nul
for /f "skip=1 tokens=1,2" %%D in ('""%ADB%" devices"') do if "%%E"=="device" (
  set "SERIAL=%%D"
  set /a COUNT+=1 >nul
)
if "%COUNT%"=="1" goto check_device
"%ADB%" devices
echo Kurulum icin bir cihaz secin. APK yolundan veya --build seceneginden sonra cihaz seri numarasini yazin.
exit /b 3

:check_device
"%ADB%" -s "%SERIAL%" get-state 2>nul | "%SystemRoot%\System32\findstr.exe" /x /c:"device" >nul
if errorlevel 1 (
  echo Secilen cihaza erisilemiyor. Baglantiyi ve cihazdaki ADB iznini kontrol edin.
  exit /b 3
)
"%ADB%" -s "%SERIAL%" install -r "%APK%"
if errorlevel 1 exit /b 1
exit /b 0

:list
"%ADB%" devices -l
exit /b %ERRORLEVEL%

:signing
echo Imzali derleme icin PERFMON_KEYSTORE, KEYSTORE_PASSWORD, KEY_ALIAS ve KEY_PASSWORD tanimli olmali.
exit /b 2

:usage_error
call :help
exit /b 2

:help
echo kurulum.cmd --list
echo kurulum.cmd "PerfMon-Plus.apk" [CIHAZ_SERI_NUMARASI]
echo kurulum.cmd --build [CIHAZ_SERI_NUMARASI]
echo Tek yetkili cihaz varsa seri numarasi istege baglidir. Guncelleme mevcut verileri korur.
exit /b 0
