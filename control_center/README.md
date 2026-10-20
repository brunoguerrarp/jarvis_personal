# J.A.R.V.I.S. — Central de Controle (PC)

Janela desktop (customtkinter) que é o "cockpit" do PC: conversa com o THOR
(Claude Code) pela mesma ponte MQTT do celular, mostra o status da conexão e
controla o daemon.

## O que tem
- **Chat com o THOR** — digita e fala comigo direto do PC. Quando chega um
  **plano** (tarefa que altera arquivos), aparecem **Confirmar / Cancelar**.
- **Luzes de status** — *Broker* (conexão desta janela ao MQTT) e *Daemon*
  (ponte viva no PC, checada pela conexão na porta 8883).
- **Painel do daemon** — log ao vivo de `bridge/logs/bridge.log` e os botões
  **Iniciar / Reiniciar / Parar** (Reiniciar mata o processo; o `run_bridge.cmd`
  relança em ~10s).
- **Projeto** — campo opcional para mirar uma pasta do workspace (+ caixa "novo").

## Como abrir
Duplo-clique em `run_control.cmd`, ou:

```
bridge\.venv\Scripts\python.exe control_center\jarvis_control.py
```

## Configuração
Lê as credenciais do broker de `bridge/.env` (fonte única: `MQTT_HOST`,
`MQTT_PORT`, `MQTT_TLS`, `MQTT_USER`, `MQTT_PASS`, `TOPIC_PREFIX`). Usa a venv do
bridge (`bridge/.venv`), que já tem `paho-mqtt`, `truststore`, `python-dotenv` e
`customtkinter`.
