"""
J.A.R.V.I.S. — avisa o celular a partir do PC.

Publica uma resposta no tópico `<prefix>/replies` do MQTT para que o
`BridgeService` do app Android mostre a notificação no celular — mesmo quando o
pedido foi feito direto aqui no PC (terminal/Claude Code), fora da ponte normal.

Uso:
    python notify_phone.py "Pedido concluído, Senhor."
    python notify_phone.py --type plan  "Aguardando sua confirmação…"
    python notify_phone.py --type error "Não consegui concluir."

Tipos (como o celular interpreta):
    result (padrão) -> 🟢 Pedido concluído
    plan            -> 🟡 Aguardando sua confirmação
    error           -> 🔴 Não consegui concluir
    status          -> atualiza discretamente a notificação fixa

Reutiliza bridge/.env (fonte única). Rode com a venv do bridge.
"""
from __future__ import annotations

import argparse
import json
import ssl
import sys
import uuid
from pathlib import Path

from dotenv import dotenv_values

try:
    import truststore

    truststore.inject_into_ssl()
except Exception:  # noqa: BLE001 - opcional (TLS atrás de proxy/antivírus)
    pass

import paho.mqtt.client as mqtt
from paho.mqtt.enums import CallbackAPIVersion

ENV_PATH = Path(__file__).resolve().parent / ".env"


def carregar_cfg() -> dict:
    cfg = dotenv_values(ENV_PATH)
    use_tls = (cfg.get("MQTT_TLS", "true") or "true").lower() not in ("0", "false", "no")
    return {
        "host": (cfg.get("MQTT_HOST") or "").strip(),
        "port": int(cfg.get("MQTT_PORT") or (8883 if use_tls else 1883)),
        "user": cfg.get("MQTT_USER", "") or "",
        "password": cfg.get("MQTT_PASS", "") or "",
        "tls": use_tls,
        "prefix": (cfg.get("TOPIC_PREFIX") or "jarvis").rstrip("/"),
    }


def enviar(texto: str, tipo: str = "result", rid: str | None = None, timeout: float = 15.0) -> bool:
    cfg = carregar_cfg()
    if not cfg["host"]:
        print(f"MQTT_HOST vazio em {ENV_PATH}", file=sys.stderr)
        return False

    rid = rid or uuid.uuid4().hex[:12]
    payload = json.dumps(
        {"id": rid, "type": tipo, "text": texto, "ok": tipo != "error"},
        ensure_ascii=False,
    )

    c = mqtt.Client(CallbackAPIVersion.VERSION2, client_id=f"jarvis-pc-notify-{uuid.uuid4().hex[:8]}")
    if cfg["user"]:
        c.username_pw_set(cfg["user"], cfg["password"])
    if cfg["tls"]:
        c.tls_set_context(ssl.create_default_context())

    try:
        c.connect(cfg["host"], cfg["port"], keepalive=30)
    except Exception as e:  # noqa: BLE001
        print(f"Falha ao conectar no broker: {e}", file=sys.stderr)
        return False

    c.loop_start()
    try:
        info = c.publish(f"{cfg['prefix']}/replies", payload, qos=1)
        info.wait_for_publish(timeout)
        entregue = info.is_published()
    except Exception as e:  # noqa: BLE001
        print(f"Falha ao publicar: {e}", file=sys.stderr)
        entregue = False
    finally:
        c.loop_stop()
        try:
            c.disconnect()
        except Exception:  # noqa: BLE001
            pass
    return entregue


def main() -> int:
    p = argparse.ArgumentParser(description="Avisa o celular do J.A.R.V.I.S. a partir do PC.")
    p.add_argument("texto", help="Mensagem a exibir no celular.")
    p.add_argument("--type", default="result", choices=["result", "plan", "error", "status"],
                   dest="tipo", help="Tipo do aviso (padrão: result).")
    p.add_argument("--id", default=None, dest="rid", help="Id opcional do pedido.")
    args = p.parse_args()

    ok = enviar(args.texto, args.tipo, args.rid)
    print("Aviso enviado ao celular." if ok else "Não consegui enviar o aviso.", file=sys.stderr)
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
