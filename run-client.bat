@echo off
cd /d "%~dp0"
echo ======================================
echo   Dang khoi dong Client (JavaFX)...
echo ======================================
call mvn -q javafx:run
pause
