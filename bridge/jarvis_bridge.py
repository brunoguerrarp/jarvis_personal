"""
J.A.R.V.I.S. — Ponte PC (daemon).

Fica ouvindo um broker MQTT e, quando chega um pedido de desenvolvimento vindo do
celular, dispara o **Claude Code em modo headless** (`claude -p`) na pasta do
projeto certo, no modelo *confirma-antes*:

    1. Chega o pedido   -> roda o Claude numa passada SÓ-LEITURA (plano) e devolve
                           ao celular um resumo "Vou fazer X. Confirmo?".
    2. Chega o "sim"    -> RETOMA a mesma sessão do Claude (`--resume`) já com
                           permissão de escrever e executa de verdade.
    3. Chega o "não"    -> descarta o pedido pendente.

As respostas voltam pelo próprio MQTT, para o celular falar em voz alta.

Config: copie `.env.example` para `.env` e preencha (credenciais do broker,
raiz do workspace e, se preciso, o caminho do executável `claude`).

Uso:
    pip install -r requirements.txt
    python jarvis_bridge.py
"""

from __future__ import annotations

import json
import logging
import os
import re
import shlex
import ssl
import subprocess
import sys
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv

try:
    import paho.mqtt.client as mqtt
    from paho.mqtt.enums import CallbackAPIVersion
except ImportError:
    sys.exit(
        "Biblioteca 'paho-mqtt' não encontrada.\n"
        "Instale as dependências com:  pip install -r requirements.txt"
    )

# Faz o Python confiar na loja de certificados do Windows. Sem isso o TLS falha
# com "unable to get local issuer certificate" em máquinas cujo proxy/antivírus
# intercepta TLS (o certifi não tem o CA injetado). Mesmo truque do jarvis.py.
try:
    import truststore

    truststore.inject_into_ssl()
except Exception:  # noqa: BLE001 - opcional
    pass


# --------------------------------------------------------------------------- #
# Configuração (lida do .env)
# --------------------------------------------------------------------------- #
@dataclass(frozen=True)
class Config:
    host: str
    port: int
    user: str
    password: str
    use_tls: bool
    prefix: str
    workspace_root: Path
    default_project: str
    claude_bin: str
    plan_timeout: int
    exec_timeout: int
    exec_permission_mode: str
    extra_dirs: tuple[Path, ...]  # pastas fora do workspace que o Claude pode ler

    @property
    def topic_requests(self) -> str:
        return f"{self.prefix}/requests"

    @property
    def topic_confirm(self) -> str:
        return f"{self.prefix}/confirm"

    @property
    def topic_replies(self) -> str:
        return f"{self.prefix}/replies"


def carregar_config() -> Config:
    load_dotenv()

    host = os.getenv("MQTT_HOST", "").strip()
    if not host:
        sys.exit(
            "MQTT_HOST não definido. Copie bridge/.env.example para bridge/.env e "
            "preencha as credenciais do seu broker (veja o README)."
        )

    use_tls = os.getenv("MQTT_TLS", "true").lower() not in ("0", "false", "no")
    port = int(os.getenv("MQTT_PORT", "8883" if use_tls else "1883"))

    raiz = Path(os.getenv("WORKSPACE_ROOT", str(Path.cwd().parent))).expanduser().resolve()

    # Pastas EXTRA que o Claude pode ler (fora do workspace), separadas por ";" ou ",".
    # Padrão: o OpenJarvis, para perguntas sobre aquele projeto. Só entram as que existem.
    extra_raw = os.getenv("EXTRA_DIRS", r"X:\Intelij_WorkSpace\OpenJarvis")
    extras: list[Path] = []
    for parte in re.split(r"[;,]", extra_raw):
        parte = parte.strip()
        if not parte:
            continue
        d = Path(parte).expanduser().resolve()
        if d.is_dir() and d not in extras and d != raiz:
            extras.append(d)

    return Config(
        host=host,
        port=port,
        user=os.getenv("MQTT_USER", ""),
        password=os.getenv("MQTT_PASS", ""),
        use_tls=use_tls,
        prefix=os.getenv("TOPIC_PREFIX", "jarvis").rstrip("/"),
        workspace_root=raiz,
        default_project=os.getenv("DEFAULT_PROJECT", "").strip(),
        claude_bin=os.getenv("CLAUDE_BIN", "claude"),
        plan_timeout=int(os.getenv("CLAUDE_PLAN_TIMEOUT", "180")),
        exec_timeout=int(os.getenv("CLAUDE_EXEC_TIMEOUT", "900")),
        # acceptEdits: aceita edições de arquivo sozinho, mas NÃO roda comandos de
        # shell sem permissão (mais seguro). Para autonomia total (build/testes),
        # troque para "bypassPermissions" — ciente do risco.
        exec_permission_mode=os.getenv("CLAUDE_EXEC_PERMISSION_MODE", "acceptEdits"),
        extra_dirs=tuple(extras),
    )


def _dirs_add(cfg: Config) -> list[str]:
    """Args --add-dir: o workspace + as pastas extras (ex.: OpenJarvis) p/ o Claude ler."""
    args: list[str] = ["--add-dir", str(cfg.workspace_root)]
    for d in cfg.extra_dirs:
        args += ["--add-dir", str(d)]
    return args


# --------------------------------------------------------------------------- #
# Execução do Claude Code (headless)
# --------------------------------------------------------------------------- #
PLAN_SUFFIX = (
    "\n\n[Instrução do sistema de ponte] Apenas PLANEJE: não altere nenhum arquivo "
    "nem rode comandos agora. Responda em 1–2 frases curtas, em português, no tom do "
    "mordomo J.A.R.V.I.S., dizendo o que o Senhor pediu e o que você fará, terminando "
    "com uma pergunta de confirmação. Ex.: 'Pretendo adicionar X ao projeto Y. "
    "Confirmo, Senhor?'"
)

EXEC_PROMPT = (
    "O Senhor confirmou. Execute agora o que foi planejado. Ao terminar, responda em "
    "1–3 frases curtas, em português, no tom do J.A.R.V.I.S., resumindo o que foi feito."
)

# Conversa/pergunta: resposta direta numa passada SÓ-LEITURA, sem o confirma-antes.
CHAT_SUFFIX = (
    "\n\n[Instrução do sistema de ponte] Responda agora, de forma direta e útil, em "
    "português, no tom do mordomo J.A.R.V.I.S.. Você PODE ler arquivos do projeto para "
    "embasar a resposta, mas NÃO altere nada e NÃO proponha um plano — apenas responda. "
    "Seja conciso (até ~4 frases), salvo se o Senhor pedir mais detalhes."
)

# Verbos/indícios de que o pedido MODIFICA algo (merece o fluxo confirma-antes). Sem
# nenhum deles, tratamos como conversa/pergunta e respondemos direto (rápido).
_ACAO = re.compile(
    r"\b("
    r"cria|criar|crie|adiciona|adicionar|adicione|implementa|implementar|implemente|"
    r"corrige|corrigir|corrija|conserta|consertar|conserte|muda|mudar|mude|altera|"
    r"alterar|altere|refatora|refatorar|refatore|remove|remover|remova|apaga|apagar|"
    r"apague|deleta|deletar|delete|instala|instalar|instale|roda|rodar|rode|executa|"
    r"executar|execute|build|compila|compilar|compile|commit|commita|commitar|"
    r"renomeia|renomear|renomeie|escreve|escrever|escreva|gera|gerar|gere|atualiza|"
    r"atualizar|atualize|configura|configurar|configure|move|mover|mova|substitui|"
    r"substituir|substitua|ajusta|ajustar|ajuste|troca|trocar|troque|"
    # Controle do PC (abrir/fechar apps, ligar/iniciar/encerrar) -> também é TAREFA,
    # para entrar no confirma-antes e poder rodar shell na execução.
    r"abre|abrir|abra|fecha|fechar|feche|liga|ligar|ligue|desliga|desligar|"
    r"desligue|inicia|iniciar|inicie|encerra|encerrar|encerre|reinicia|"
    r"reiniciar|reinicie|mata|matar|mate"
    r")\b",
    re.IGNORECASE,
)


def eh_tarefa(texto: str) -> bool:
    """True se o pedido parece MODIFICAR o projeto (usa o fluxo confirma-antes)."""
    return bool(_ACAO.search(texto))


@dataclass
class ClaudeResult:
    text: str
    session_id: str | None
    is_error: bool


def _rodar_claude(cmd: list[str], cwd: Path, timeout: int) -> ClaudeResult:
    """Roda o Claude Code headless e extrai o texto e o session_id do JSON."""
    log.info("Claude: %s  (cwd=%s)", " ".join(shlex.quote(c) for c in cmd), cwd)
    try:
        proc = subprocess.run(
            cmd,
            cwd=str(cwd),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )
    except subprocess.TimeoutExpired:
        return ClaudeResult(
            text=f"A operação excedeu {timeout}s e foi interrompida, Senhor.",
            session_id=None,
            is_error=True,
        )
    except FileNotFoundError:
        return ClaudeResult(
            text=(
                "Não encontrei o executável 'claude' no PC. Ajuste CLAUDE_BIN no .env "
                "com o caminho completo."
            ),
            session_id=None,
            is_error=True,
        )

    saida = (proc.stdout or "").strip()
    erro = (proc.stderr or "").strip()

    # `--output-format json` devolve um único objeto JSON com result/session_id.
    try:
        obj = json.loads(saida)
        texto = (obj.get("result") or "").strip()
        return ClaudeResult(
            text=texto or "(sem texto de resposta)",
            session_id=obj.get("session_id"),
            is_error=bool(obj.get("is_error")) or proc.returncode != 0,
        )
    except (json.JSONDecodeError, AttributeError):
        # Fallback: não veio JSON (erro de CLI, etc.). Usa stdout/stderr cru.
        texto = saida or erro or "(sem saída do Claude)"
        return ClaudeResult(
            text=texto,
            session_id=None,
            is_error=proc.returncode != 0,
        )


def planejar(cfg: Config, texto: str, projeto_dir: Path) -> ClaudeResult:
    """Passada SÓ-LEITURA: o Claude descreve o que fará, sem tocar em nada."""
    cmd = [
        cfg.claude_bin,
        "-p",
        texto + PLAN_SUFFIX,
        "--output-format", "json",
        "--permission-mode", "plan",
        "--allowedTools", "Read,Grep,Glob",
        *_dirs_add(cfg),
    ]
    return _rodar_claude(cmd, projeto_dir, cfg.plan_timeout)


def conversar(cfg: Config, texto: str, projeto_dir: Path) -> ClaudeResult:
    """Pergunta/conversa: resposta direta numa passada só-leitura (sem confirma-antes)."""
    cmd = [
        cfg.claude_bin,
        "-p",
        texto + CHAT_SUFFIX,
        "--output-format", "json",
        "--permission-mode", "plan",
        "--allowedTools", "Read,Grep,Glob",
        *_dirs_add(cfg),
    ]
    return _rodar_claude(cmd, projeto_dir, cfg.plan_timeout)


def executar(cfg: Config, session_id: str, projeto_dir: Path) -> ClaudeResult:
    """Passada de execução: retoma a MESMA sessão já com permissão de escrever."""
    cmd = [
        cfg.claude_bin,
        "--resume", session_id,
        "-p", EXEC_PROMPT,
        "--output-format", "json",
        "--permission-mode", cfg.exec_permission_mode,
        *_dirs_add(cfg),
    ]
    return _rodar_claude(cmd, projeto_dir, cfg.exec_timeout)


# --------------------------------------------------------------------------- #
# Resolução de projeto (dentro do workspace, sem escapar com "..")
# --------------------------------------------------------------------------- #
_NOME_OK = re.compile(r"^[A-Za-z0-9 ._-]{1,80}$")


def resolver_projeto(cfg: Config, nome: str | None, criar_novo: bool) -> tuple[Path, str | None]:
    """Devolve (pasta, erro). A pasta fica garantidamente dentro do workspace."""
    bruto = (nome or cfg.default_project or "").strip()

    # Sem projeto informado: trabalha na raiz do workspace (o Claude enxerga tudo).
    if not bruto:
        return cfg.workspace_root, None

    if not _NOME_OK.match(bruto):
        return cfg.workspace_root, f"Nome de projeto inválido: {bruto!r}."

    # Aceita "jarvis personal" falado -> "jarvis_personal".
    slug = bruto.strip().replace(" ", "_")
    alvo = (cfg.workspace_root / slug).resolve()

    # Trava anti-travessia: o alvo TEM que estar sob o workspace.
    if cfg.workspace_root not in alvo.parents and alvo != cfg.workspace_root:
        return cfg.workspace_root, "Caminho fora do workspace recusado."

    if alvo.exists():
        return alvo, None
    if criar_novo:
        alvo.mkdir(parents=True, exist_ok=True)
        log.info("Projeto novo criado: %s", alvo)
        return alvo, None
    # Projeto não encontrado e não é criação: em vez de BLOQUEAR o pedido (o que
    # travava perguntas por uma palavra solta, ex. "projeto do OpenJarvis"), trabalha
    # na raiz do workspace e responde assim mesmo. Criação continua via flag `new`.
    log.info("Projeto '%s' não existe; usando a raiz do workspace.", slug)
    return cfg.workspace_root, None


# --------------------------------------------------------------------------- #
# Ponte MQTT
# --------------------------------------------------------------------------- #
class Ponte:
    def __init__(self, cfg: Config) -> None:
        self.cfg = cfg
        # {id_pedido -> (session_id, projeto_dir)}
        self.pendentes: dict[str, tuple[str, Path]] = {}
        self._lock = threading.Lock()
        self._pool = ThreadPoolExecutor(max_workers=2, thread_name_prefix="claude")

        self.client = mqtt.Client(
            CallbackAPIVersion.VERSION2,
            client_id=f"jarvis-pc-{uuid.uuid4().hex[:8]}",
        )
        if cfg.user:
            self.client.username_pw_set(cfg.user, cfg.password)
        if cfg.use_tls:
            # Contexto padrão já corrigido pelo truststore (loja do Windows).
            self.client.tls_set_context(ssl.create_default_context())
        self.client.reconnect_delay_set(min_delay=1, max_delay=30)
        self.client.on_connect = self._on_connect
        self.client.on_disconnect = self._on_disconnect
        self.client.on_message = self._on_message

    # ----- publicação ------------------------------------------------------- #
    def responder(self, id_pedido: str, tipo: str, texto: str, ok: bool = True) -> None:
        payload = json.dumps(
            {"id": id_pedido, "type": tipo, "text": texto, "ok": ok},
            ensure_ascii=False,
        )
        self.client.publish(self.cfg.topic_replies, payload, qos=1)
        log.info("-> reply [%s] %s: %s", tipo, id_pedido, texto[:120])

    # ----- callbacks MQTT --------------------------------------------------- #
    def _on_connect(self, client, userdata, flags, reason_code, properties) -> None:
        if reason_code != 0:
            log.error("Falha ao conectar no broker: %s", reason_code)
            return
        client.subscribe([(self.cfg.topic_requests, 1), (self.cfg.topic_confirm, 1)])
        log.info(
            "Conectado a %s:%s. Ouvindo '%s' e '%s'.",
            self.cfg.host, self.cfg.port, self.cfg.topic_requests, self.cfg.topic_confirm,
        )

    def _on_disconnect(self, client, userdata, flags, reason_code, properties) -> None:
        log.warning("Desconectado (%s). Tentando reconectar...", reason_code)

    def _on_message(self, client, userdata, msg) -> None:
        try:
            dados = json.loads(msg.payload.decode("utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError) as e:
            log.warning("Mensagem inválida em %s: %s", msg.topic, e)
            return

        # Processa fora da thread de rede para não travar o keep-alive do MQTT.
        if msg.topic == self.cfg.topic_requests:
            self._pool.submit(self._tratar_pedido, dados)
        elif msg.topic == self.cfg.topic_confirm:
            self._pool.submit(self._tratar_confirmacao, dados)

    # ----- lógica ----------------------------------------------------------- #
    def _tratar_pedido(self, dados: dict) -> None:
        id_pedido = str(dados.get("id") or uuid.uuid4().hex[:12])
        texto = (dados.get("text") or "").strip()
        if not texto:
            self.responder(id_pedido, "error", "Pedido vazio, Senhor.", ok=False)
            return

        projeto_dir, erro = resolver_projeto(
            self.cfg, dados.get("project"), bool(dados.get("new"))
        )
        if erro:
            self.responder(id_pedido, "error", erro, ok=False)
            return

        log.info("Pedido [%s] em %s: %s", id_pedido, projeto_dir.name, texto)

        # Conversa/pergunta: resposta DIRETA numa só passada (rápido, sem confirmação),
        # pelo Claude (eu) em modo só-leitura.
        if not eh_tarefa(texto):
            self.responder(id_pedido, "status", "Um momento, Senhor.")
            res = conversar(self.cfg, texto, projeto_dir)
            self.responder(
                id_pedido,
                "error" if res.is_error else "result",
                res.text or "Peço perdão, Senhor; fiquei sem palavras.",
                ok=not res.is_error,
            )
            return

        # Tarefa que altera o projeto: fluxo confirma-antes (plano -> sim -> executa).
        self.responder(id_pedido, "status", "Um momento, Senhor; analisando o pedido.")

        res = planejar(self.cfg, texto, projeto_dir)
        if res.is_error or not res.session_id:
            self.responder(
                id_pedido, "error",
                res.text or "Não consegui analisar o pedido, Senhor.",
                ok=False,
            )
            return

        with self._lock:
            self.pendentes[id_pedido] = (res.session_id, projeto_dir)
        self.responder(id_pedido, "plan", res.text)

    def _tratar_confirmacao(self, dados: dict) -> None:
        id_pedido = str(dados.get("id") or "")
        ok = bool(dados.get("ok"))

        with self._lock:
            pendente = self.pendentes.pop(id_pedido, None)

        if pendente is None:
            self.responder(id_pedido, "error", "Não há pedido pendente com esse id, Senhor.", ok=False)
            return
        if not ok:
            self.responder(id_pedido, "status", "Pois não, Senhor. Pedido cancelado.")
            return

        session_id, projeto_dir = pendente
        self.responder(id_pedido, "status", "Imediatamente, Senhor. Executando.")
        res = executar(self.cfg, session_id, projeto_dir)
        self.responder(
            id_pedido,
            "error" if res.is_error else "result",
            res.text,
            ok=not res.is_error,
        )

    # ----- ciclo de vida ---------------------------------------------------- #
    def rodar(self) -> None:
        self.client.connect(self.cfg.host, self.cfg.port, keepalive=60)
        self.client.loop_start()
        log.info("Ponte J.A.R.V.I.S. no ar. Ctrl+C para encerrar.")
        parar = threading.Event()
        try:
            parar.wait()
        except KeyboardInterrupt:
            log.info("Encerrando...")
        finally:
            self.client.loop_stop()
            self.client.disconnect()
            self._pool.shutdown(wait=False, cancel_futures=True)


# --------------------------------------------------------------------------- #
log = logging.getLogger("jarvis.bridge")


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )
    cfg = carregar_config()
    log.info("Workspace: %s | prefixo de tópicos: %s", cfg.workspace_root, cfg.prefix)
    Ponte(cfg).rodar()


if __name__ == "__main__":
    main()
