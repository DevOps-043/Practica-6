@echo off
setlocal
cd /d "%~dp0"
echo Preparando Java, Maven y SQLite para esta computadora...
echo La primera preparacion necesita conexion a Internet.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0iniciar.ps1" -Action prepare
if errorlevel 1 (
    echo No se pudo completar la preparacion. Revisa el error anterior.
    pause
    exit /b 1
)
echo.
echo Preparacion terminada. Ahora ejecuta iniciar.cmd.
pause
