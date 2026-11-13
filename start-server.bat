@echo off
cd /d "%~dp0server"
if not "%~1"=="" set PORT=%~1
call npm start
pause
