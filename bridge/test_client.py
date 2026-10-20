"""
Cliente de teste da Ponte J.A.R.V.I.S. — faz o papel do celular.

Permite exercitar o daemon (jarvis_bridge.py) sem o app Android: você digita um
pedido, ele publica em <prefixo>/requests, mostra o plano que o Claude devolve e
pergunta se confirma (s/n) — publicando em <prefixo>/confirm.

Uso (no mesmo PC ou em outro, com o mesmo .env):
    python test_client.py
    python test_client.py --project jarvis_personal "adicione um comando /status"
"""

from __future__ import annotations

import argparse
import json
import ssl
import sys
import threading
import uuid

# Importar jarvis_bridge já injeta o truststore (confia na loja de certs do Windows).
from jarvis_bridge import carregar_config  # reaproveita a leitura do .env

try:
    import paho.mqtt.client as mqtt
    from paho.mqtt.enums import CallbackAPIVersion
except ImportError:
    sys.exit("Instale as dependências:  pip install -r requirements.txt")


def main() -> None:
    ap = argparse.ArgumentParser(description="Cliente de teste da ponte (faz de celular).")
    ap.add_argument("--project", default=None, help="Nome do projeto (pasta no workspace).")
    ap.add_argument("--new", action="store_true", help="Pedir para criar um projeto novo.")
    ap.add_argument("texto", nargs="*", help="O pedido. Se vazio, entra em modo interativo.")
    args = ap.parse_args()

    cfg = carregar_config()
    pendente_id: dict[str, str | None] = {"id": None}

    client = mqtt.Client(CallbackAPIVersion.VERSION2, client_id=f"jarvis-test-{uuid.uuid4().hex[:6]}")
    if cfg.user:
        client.username_pw_set(cfg.user, cfg.password)
    if cfg.use_tls:
        client.tls_set_context(ssl.create_default_context())

    def on_connect(c, u, flags, rc, props):
        if rc != 0:
            print(f"[erro] conexão recusada: {rc}")
            return
        c.subscribe(cfg.topic_replies, qos=1)
        print(f"[ok] conectado; ouvindo {cfg.topic_replies}\n")

    def on_message(c, u, msg):
        try:
            d = json.loads(msg.payload.decode("utf-8"))
        except Exception:
            print(f"[reply bruto] {msg.payload!r}")
            return
        tipo = d.get("type")
        print(f"\n[{tipo}] {d.get('text')}")
        if tipo == "plan":
            pendente_id["id"] = d.get("id")
            print("  -> confirmar? digite 's' (sim) ou 'n' (não) e Enter.")
        else:
            print("Senhor > ", end="", flush=True)

    client.on_connect = on_connect
    client.on_message = on_message
    client.connect(cfg.host, cfg.port, keepalive=60)
    client.loop_start()

    def publicar_pedido(texto: str) -> None:
        pid = uuid.uuid4().hex[:12]
        payload = {"id": pid, "text": texto}
        if args.project:
            payload["project"] = args.project
        if args.new:
            payload["new"] = True
        client.publish(cfg.topic_requests, json.dumps(payload, ensure_ascii=False), qos=1)
        print(f"[enviado] id={pid} projeto={args.project or '(raiz)'}")

    # Modo one-shot (texto na linha de comando).
    if args.texto:
        publicar_pedido(" ".join(args.texto))
        threading.Event().wait(2)  # dá tempo de ver o status inicial

    # Loop interativo: pedidos e confirmações pelo teclado.
    try:
        while True:
            linha = input("Senhor > ").strip()
            if not linha:
                continue
            if linha.lower() in ("sair", "quit", "exit"):
                break
            baixo = linha.lower()
            if pendente_id["id"] and baixo in ("s", "sim", "n", "nao", "não"):
                ok = baixo in ("s", "sim")
                client.publish(
                    cfg.topic_confirm,
                    json.dumps({"id": pendente_id["id"], "ok": ok}),
                    qos=1,
                )
                print(f"[confirmação enviada] ok={ok}")
                pendente_id["id"] = None
            else:
                publicar_pedido(linha)
    except (EOFError, KeyboardInterrupt):
        pass
    finally:
        client.loop_stop()
        client.disconnect()


if __name__ == "__main__":
    main()
