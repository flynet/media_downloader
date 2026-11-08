@echo off
setlocal enabledelayedexpansion

rem =====================================================================
rem  HLS Grabber - full build -> app-image .exe -> launch GUI
rem  Steps: 1) mvn clean package  2) jlink runtime  3) jpackage app-image
rem         4) bundle Playwright browsers  5) launch GUI
rem  Requires: JDK 21 (Temurin) and Maven 3.9+, Windows x64.
rem  Edit JDK=/MAVEN_CMD below to point to YOUR install (paths here are the
rem  author's). See README section "Building from Sources".
rem =====================================================================

set "ROOT=%~dp0"
set "JDK=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
set "JAVA_HOME=%JDK%"
set "MAVEN_CMD=C:\mvn\bin\mvn.cmd"

set "HUB=%ROOT%Downloader_hub"
set "APP=%ROOT%appimage"

rem ---- Requirement checks (friendly, before any build work) ----
if not exist "%JDK%\bin\jlink.exe" (
  echo ERROR: JDK 21 not found at "%JDK%".
  echo Install it, e.g.:  winget install EclipseAdoptium.Temurin.21.JDK
  echo Then set the JDK= path at the top of this script to your install.
  goto :fail
)
if not exist "%MAVEN_CMD%" (
  echo ERROR: Maven not found at "%MAVEN_CMD%".
  echo Download "apache-maven-3.x-bin.zip" from https://maven.apache.org/download.cgi
  echo Extract it ^(e.g. to C:\mvn^), then set MAVEN_CMD= at the top of this script.
  goto :fail
)
"%JDK%\bin\java" -version 2>&1 | findstr /C:"21." >nul || (
  echo ERROR: the JDK at "%JDK%" is not version 21. This build needs JDK 21.
  goto :fail
)

echo [1/5] Maven clean package...
call "%MAVEN_CMD%" -f "%HUB%\pom.xml" clean package
if errorlevel 1 goto :fail

set "JAR=%HUB%\target\media_downloader.jar"
if not exist "%JAR%" ( echo ERROR: jar not found: %JAR% & goto :fail )

echo [2/5] jlink runtime...
if exist "%APP%\runtime" rmdir /s /q "%APP%\runtime"
"%JDK%\bin\jlink.exe" --module-path "%JDK%\jmods" ^
  --add-modules java.base,java.compiler,java.desktop,java.management,java.naming,java.net.http,java.sql,jdk.unsupported,jdk.jcmd,jdk.httpserver,jdk.crypto.ec,jdk.charsets,jdk.compiler ^
  --output "%APP%\runtime" --strip-debug --no-man-pages --no-header-files --compress zip-6
if errorlevel 1 goto :fail

echo [3/5] jpackage app-image (with icon)...
if exist "%APP%\dist" rmdir /s /q "%APP%\dist"
if not exist "%APP%\input" mkdir "%APP%\input"
del /q "%APP%\input\*.jar" >nul 2>nul
copy /Y "%JAR%" "%APP%\input\" >nul
"%JDK%\bin\jpackage.exe" --type app-image --name "HLS Grabber" ^
  --input "%APP%\input" --main-jar media_downloader.jar ^
  --main-class com.flynet.hlsgrabber.App ^
  --runtime-image "%APP%\runtime" ^
  --icon "%HUB%\src\main\resources\icon.ico" ^
  --dest "%APP%\dist"
if errorlevel 1 goto :fail

set "EXE=%APP%\dist\HLS Grabber\HLS Grabber.exe"
if not exist "%EXE%" ( echo ERROR: exe not found & goto :fail )

echo [4/5] Bundling Playwright browsers into the app...
set "BROWSERS=%APPDATA%\..\Local\ms-playwright"
set "APPDIR=%APP%\dist\HLS Grabber\app"
for %%d in (chromium-1248 chromium_headless_shell-1248 ffmpeg-1013 winldd-1007) do (
  if exist "%BROWSERS%\%%d" (
    xcopy /E /Y /I /Q "%BROWSERS%\%%d" "!APPDIR!\browsers\%%d" >nul
    echo   bundled: %%d
  ) else (
    echo   WARNING: !BROWSERS!\%%d not found - run the app once with Chrome installed and rebuild
  )
)

echo [5/5] Launching GUI...
start "HLS Grabber" "%EXE%"

echo.
echo BUILD OK: "%EXE%"
echo   - icon: src\main\resources\icon.ico
echo   - browsers bundled: app\browsers
echo   - settings/profile: %%APPDATA%%\hlsgrabber (unchanged)
exit /b 0

:fail
echo.
echo BUILD FAILED - see messages above.
exit /b 1
