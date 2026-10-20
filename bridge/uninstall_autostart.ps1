# Remove o autostart do daemon do Jarvis. Rode:
#     powershell -ExecutionPolicy Bypass -File .\uninstall_autostart.ps1
$ErrorActionPreference = 'Stop'
$taskName = 'JarvisBridge'

$existe = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
if ($null -eq $existe) {
    Write-Host "Tarefa '$taskName' nao existe. Nada a remover." -ForegroundColor Yellow
    return
}

try { Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue } catch {}
Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
Write-Host "OK: tarefa '$taskName' removida. Feche manualmente um python.exe que ainda esteja rodando." -ForegroundColor Green
