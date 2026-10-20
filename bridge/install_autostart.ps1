# ---------------------------------------------------------------------------
# Registra o daemon do Jarvis para subir sozinho no SEU logon do Windows,
# escondido (sem janela) e com reinicio automatico. Rode UMA vez:
#
#     powershell -ExecutionPolicy Bypass -File .\install_autostart.ps1
#
# Nao precisa de admin: a tarefa roda na sua sessao de usuario, entao o drive
# X: mapeado e o login do Claude Code ficam disponiveis. Para remover depois,
# use uninstall_autostart.ps1.
# ---------------------------------------------------------------------------
$ErrorActionPreference = 'Stop'

$taskName = 'JarvisBridge'
$bridgeDir = $PSScriptRoot
$vbs = Join-Path $bridgeDir 'start_bridge_hidden.vbs'

if (-not (Test-Path $vbs)) {
    throw "Nao achei $vbs. Rode este script de dentro da pasta bridge."
}

# Dispara wscript.exe (ele roda o .vbs, que roda o .cmd oculto).
$action = New-ScheduledTaskAction -Execute 'wscript.exe' -Argument ('"{0}"' -f $vbs)

# Gatilho: no logon do usuario atual, com 30s de folga para o drive X: montar.
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$trigger.Delay = 'PT30S'

# Sem limite de tempo; reinicia ate 3x de 1 em 1 min se a tarefa falhar;
# roda com ou sem energia na tomada.
$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -RestartCount 3 `
    -RestartInterval (New-TimeSpan -Minutes 1) `
    -MultipleInstances IgnoreNew

$principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel Limited

Register-ScheduledTask `
    -TaskName $taskName `
    -Action $action `
    -Trigger $trigger `
    -Settings $settings `
    -Principal $principal `
    -Description 'Daemon do Jarvis (ponte celular-PC por MQTT). Sobe oculto no logon.' `
    -Force | Out-Null

Write-Host "OK: tarefa '$taskName' registrada. Sobe no seu proximo logon." -ForegroundColor Green
Write-Host ""
Write-Host "Iniciar agora sem reiniciar o PC:" -ForegroundColor Cyan
Write-Host "    Start-ScheduledTask -TaskName $taskName"
Write-Host "Ver o log:" -ForegroundColor Cyan
Write-Host ("    Get-Content '{0}' -Tail 30 -Wait" -f (Join-Path $bridgeDir 'logs\bridge.log'))
Write-Host "Parar o que esta rodando agora:" -ForegroundColor Cyan
Write-Host "    Stop-ScheduledTask -TaskName $taskName   depois feche eventuais python.exe"
