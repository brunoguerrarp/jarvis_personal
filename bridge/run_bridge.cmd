@echo off
rem --------------------------------------------------------------------------
rem Roda o daemon do Jarvis em loop: se ele cair (broker fora no boot, erro,
rem etc.) reinicia sozinho depois de alguns segundos. A saida vai para o log.
rem Chamado pela tarefa agendada (via start_bridge_hidden.vbs, sem janela).
rem --------------------------------------------------------------------------
setlocal
set "BRIDGE_DIR=%~dp0"
set "PY=%BRIDGE_DIR%.venv\Scripts\python.exe"
set "LOGDIR=%BRIDGE_DIR%logs"
set "LOG=%LOGDIR%\bridge.log"

if not exist "%LOGDIR%" mkdir "%LOGDIR%"
cd /d "%BRIDGE_DIR%"

:loop
echo.>> "%LOG%"
echo [%date% %time%] iniciando daemon>> "%LOG%"
"%PY%" jarvis_bridge.py >> "%LOG%" 2>&1
echo [%date% %time%] daemon encerrou (codigo %errorlevel%); reiniciando em 10s>> "%LOG%"
timeout /t 10 /nobreak > nul
goto loop
