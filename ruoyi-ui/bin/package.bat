@echo off
setlocal
cd /d "%~dp0.."
call npm ci
endlocal
