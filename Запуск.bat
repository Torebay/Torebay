@echo off
chcp 65001 >nul
cd /d "%~dp0"
python -m assistant.main %*
if errorlevel 1 pause
