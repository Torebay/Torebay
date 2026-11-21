@echo off
rem Double-click: tablet view menu for PUBG Mobile. Needs Python and adb (see README).
chcp 65001 >nul
set PYTHONIOENCODING=utf-8
cd /d "%~dp0"
where py >nul 2>nul
if %errorlevel%==0 (
    py tablet_view.py %*
) else (
    python tablet_view.py %*
)
echo.
pause
