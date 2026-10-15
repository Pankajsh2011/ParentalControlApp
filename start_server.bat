@echo off
title Parental Control Cloud & Signaling Server
color 0b
echo ================================================================
echo       PARENTAL CONTROL REAL-TIME SYNC & RELAY SERVER
echo ================================================================
echo.
cd /d "%~dp0server"

echo Checking and starting Server on Port 5050...
echo.
node server.js
pause
