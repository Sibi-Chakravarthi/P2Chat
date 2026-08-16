@echo off
title P2Chat Receiver
cd /d "%~dp0"

echo ===================================================
echo   P2Chat Receiver Setup ^& Launcher
echo ===================================================

where python >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] Python is not installed or not in PATH!
    echo Please install Python 3.10+ and add it to PATH.
    pause
    exit /b 1
)

if not exist .venv (
    echo Creating virtual environment...
    python -m venv .venv
    call .venv\Scripts\activate.bat
    echo Installing dependencies...
    pip install -r requirements.txt
) else (
    call .venv\Scripts\activate.bat
)

python receiver.py %*
pause
