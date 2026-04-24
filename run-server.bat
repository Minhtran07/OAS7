@echo off
cd /d "%~dp0"
echo ======================================
echo   Dang bien dich project...
echo ======================================
call mvn -q compile
if errorlevel 1 (
    echo.
    echo [LOI] Bien dich that bai. Kiem tra JDK va Maven da cai chua.
    pause
    exit /b 1
)
echo.
echo ======================================
echo   Dang khoi dong Server (cong 3667)...
echo ======================================
call mvn -q exec:java "-Dexec.mainClass=com.auction.server.MainServer"
pause
