@echo off
rem Abre a Central de Controle do J.A.R.V.I.S. usando a venv do bridge.
setlocal
set "AQUI=%~dp0"
"%AQUI%..\bridge\.venv\Scripts\python.exe" "%AQUI%jarvis_control.py"
