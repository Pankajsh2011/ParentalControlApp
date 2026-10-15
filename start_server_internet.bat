@echo off
title Parental Control Internet Tunnel (Ngrok)
color 0a
echo ================================================================
echo   PARENTAL CONTROL INTERNET TUNNEL (For Mobile Data / 4G / 5G)
echo ================================================================
echo.
echo Starting ngrok tunnel on port 5050...
echo Both phones will be able to connect from anywhere in the world!
echo.
ngrok http 5050
pause
