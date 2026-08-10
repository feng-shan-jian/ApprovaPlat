@echo off
setlocal
cd /d "%~dp0.."
call mvn clean package -DskipTests
endlocal
