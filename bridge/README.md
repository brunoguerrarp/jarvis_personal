# Ponte J.A.R.V.I.S. — daemon do PC

Liga o celular ao PC: um pedido de desenvolvimento dito ao Jarvis no telefone
chega aqui e dispara o **Claude Code** (headless) no projeto certo, no modelo
**confirma-antes** (plano → seu "sim" → execução).

```
Celular ──(MQTT)──▶ broker na nuvem ──(MQTT)──▶ jarvis_bridge.py ──▶ claude -p
   ▲                                                    │
   └──────────────── resposta (voz) ◀──────────────────┘
```

## 1. Instalar

```powershell
cd X:\spring-workspace\jarvis_personal\bridge
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

Precisa também do **Claude Code** instalado e logado neste PC (o daemon chama o
executável `claude`). Confira com `claude --version`.

## 2. Criar o broker (flespi — grátis)

> O HiveMQ Cloud Serverless e o EMQX Serverless só têm grátis por tempo/trial;
> usamos o **flespi**, que tem plano grátis de verdade com TLS. O daemon é
> agnóstico de broker — qualquer MQTT com TLS serve.

1. Crie conta em https://flespi.io (sem cartão).
2. No painel, vá em **Tokens** → crie um token (um **Standard token** com acesso
   completo basta). **Copie o valor do token** — é o que autentica.
3. O broker é fixo: host **`mqtt.flespi.io`**, porta **TLS 8883**.
4. No flespi a autenticação é por **token**: ele vai no **MQTT_USER** e a senha
   fica **em branco**. Os tópicos são privados da sua conta. Esse token vai no
   `.env` **e** no app Android.

> Se o cliente reclamar de credencial, tente prefixar o usuário com
> `FlespiToken ` (alguns clientes exigem).

> Alternativa sem terceiros: subir um `mosquitto`. Mas aí o PC precisaria estar
> exposto à internet para o celular alcançar fora do WiFi — por isso o broker
> gerenciado é o caminho recomendado.

## 3. Configurar

```powershell
copy .env.example .env
notepad .env
```

Preencha `MQTT_HOST/PORT/USER/PASS`, confira `WORKSPACE_ROOT` e escolha um
`TOPIC_PREFIX` com um sufixo aleatório secreto (ex.: `jarvis/a1b2c3d4`). Essa
string e as credenciais precisam bater com o app depois.

| Variável | O que é |
|---|---|
| `TOPIC_PREFIX` | Base dos tópicos. Tópicos: `<prefixo>/requests`, `/confirm`, `/replies`. |
| `WORKSPACE_ROOT` | Raiz onde o Claude pode trabalhar (travado via `--add-dir`). |
| `CLAUDE_EXEC_PERMISSION_MODE` | `acceptEdits` (seguro, padrão) ou `bypassPermissions` (autonomia total, arriscado). |

## 4. Rodar

```powershell
python jarvis_bridge.py
```

Deixe essa janela aberta. Logs mostram cada pedido, o plano e a execução.

## 5. Testar sem o app

Em **outro terminal** (mesmo `.env`):

```powershell
.\.venv\Scripts\Activate.ps1
python test_client.py --project jarvis_personal "adicione um comando /status que mostra a versão"
```

Ou interativo: `python test_client.py`, digite o pedido, veja o plano e responda
`s` (sim) ou `n` (não).

## Protocolo MQTT (o que o app Android vai falar)

Todas as mensagens são JSON.

**Celular → PC** em `<prefixo>/requests`:
```json
{ "id": "abc123", "project": "jarvis_personal", "text": "adicione X", "new": false }
```

**Celular → PC** em `<prefixo>/confirm`:
```json
{ "id": "abc123", "ok": true }
```

**PC → Celular** em `<prefixo>/replies`:
```json
{ "id": "abc123", "type": "status|plan|result|error", "text": "...", "ok": true }
```

- `status` — aviso de progresso ("analisando", "executando").
- `plan` — resumo do que será feito; **espera confirmação** (`confirm`).
- `result` — terminou; texto para o Jarvis falar.
- `error` — algo falhou (`ok=false`).

## Segurança

- A 1ª passada é **só-leitura** (`--permission-mode plan`, ferramentas `Read/Grep/Glob`),
  então o resumo que você confirma é fiel — nada é alterado antes do "sim".
- A execução fica **travada no `WORKSPACE_ROOT`** via `--add-dir`.
- Mesmo assim, o daemon roda o Claude com a sua conta na sua máquina. Mantenha o
  `TOPIC_PREFIX` secreto e o broker com senha/TLS. Para ambientes sensíveis,
  deixe `CLAUDE_EXEC_PERMISSION_MODE=acceptEdits`.

## Subir junto com o Windows (autostart)

Para o daemon ligar sozinho no seu logon, escondido (sem janela) e reiniciando
caso caia, rode **uma vez**:

```powershell
cd X:\spring-workspace\jarvis_personal\bridge
powershell -ExecutionPolicy Bypass -File .\install_autostart.ps1
```

Isso cria uma Tarefa Agendada `JarvisBridge` no seu usuário (não precisa de
admin), disparada no logon com 30s de folga para o drive `X:` montar. A saída
vai para `bridge\logs\bridge.log`.

| Ação | Comando |
|------|---------|
| Iniciar agora (sem reiniciar) | `Start-ScheduledTask -TaskName JarvisBridge` |
| Acompanhar o log | `Get-Content .\logs\bridge.log -Tail 30 -Wait` |
| Parar | `Stop-ScheduledTask -TaskName JarvisBridge` + fechar o `python.exe` |
| Remover o autostart | `powershell -ExecutionPolicy Bypass -File .\uninstall_autostart.ps1` |

**Como funciona:** a tarefa roda `wscript start_bridge_hidden.vbs`, que dispara
`run_bridge.cmd` oculto; o `.cmd` roda o daemon num laço e o reinicia 10s depois
se ele encerrar (broker fora no boot, erro, etc.). No Gerenciador de Tarefas você
verá **dois** `python.exe` com `jarvis_bridge.py`: é normal — um é o stub lançador
do venv e o outro é o daemon real (só ele abre a conexão MQTT).
