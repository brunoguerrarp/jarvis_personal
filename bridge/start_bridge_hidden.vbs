' Dispara o run_bridge.cmd SEM janela de console (0 = oculto, False = nao espera).
' Usado pela tarefa agendada para o daemon subir invisivel no logon.
Dim sh, dir
Set sh = CreateObject("WScript.Shell")
dir = CreateObject("Scripting.FileSystemObject").GetParentFolderName(WScript.ScriptFullName)
sh.Run "cmd /c """ & dir & "\run_bridge.cmd""", 0, False
