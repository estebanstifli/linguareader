@echo off
rem Lanzador de doble clic: ejecuta run.ps1 sin depender de la ExecutionPolicy del sistema.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run.ps1" %*
if errorlevel 1 pause
