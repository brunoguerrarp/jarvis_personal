"""
Hook de Stop do Claude Code — avisa o celular quando EU (sessão do terminal)
termino de responder um pedido do Bruno.

O Claude Code chama este script no evento `Stop`, passando um JSON no stdin com
`transcript_path` (o .jsonl da conversa) e `stop_hook_active`. Pegamos o resumo
da última resposta do assistente e disparamos a notificação em **segundo plano**
(processo destacado) para não adicionar latência ao turno.

Sai sempre com 0 — um aviso que falha jamais deve atrapalhar a conversa.
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

AQUI = Path(__file__).resolve().parent
PYTHON = AQUI / ".venv" / "Scripts" / "python.exe"
NOTIFY = AQUI / "notify_phone.py"
MAX = 180  # caracteres do resumo enviado ao celular


def ultima_resposta(transcript_path: str) -> str:
    """Extrai o texto da última mensagem do assistente no transcript .jsonl."""
    try:
        linhas = Path(transcript_path).read_text(encoding="utf-8", errors="replace").splitlines()
    except Exception:  # noqa: BLE001
        return ""
    for linha in reversed(linhas):
        linha = linha.strip()
        if not linha:
            continue
        try:
            o = json.loads(linha)
        except Exception:  # noqa: BLE001
            continue
        msg = o.get("message") if isinstance(o, dict) else None
        papel = (o.get("type") or (msg or {}).get("role") or "").lower()
        if papel not in ("assistant",):
            continue
        conteudo = (msg or {}).get("content") if isinstance(msg, dict) else None
        if isinstance(conteudo, str):
            txt = conteudo.strip()
            if txt:
                return txt
        elif isinstance(conteudo, list):
            partes = [
                b.get("text", "")
                for b in conteudo
                if isinstance(b, dict) and b.get("type") == "text"
            ]
            txt = " ".join(p.strip() for p in partes if p.strip()).strip()
            if txt:
                return txt
    return ""


def resumir(texto: str) -> str:
    # Uma linha, enxuta, para caber na notificação.
    linha = " ".join(texto.split())
    if len(linha) > MAX:
        linha = linha[: MAX - 1].rstrip() + "…"
    return linha


def main() -> int:
    try:
        dados = json.load(sys.stdin)
    except Exception:  # noqa: BLE001
        dados = {}

    # Evita laço: se o hook já está ativo numa continuação, não reavisa.
    if dados.get("stop_hook_active"):
        return 0

    texto = ultima_resposta(dados.get("transcript_path", ""))
    resumo = resumir(texto) if texto else "Concluí uma etapa, Senhor."

    try:
        subprocess.Popen(
            [str(PYTHON), str(NOTIFY), resumo],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            stdin=subprocess.DEVNULL,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)
            | getattr(subprocess, "DETACHED_PROCESS", 0),
            close_fds=True,
        )
    except Exception:  # noqa: BLE001
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
