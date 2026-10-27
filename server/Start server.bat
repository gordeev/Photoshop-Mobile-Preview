@echo off
cd /d "%~dp0"
python preview_server.py
if errorlevel 1 py preview_server.py
pause
