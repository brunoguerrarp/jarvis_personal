"""
J.A.R.V.I.S. — Central de Controle (PC).

Janela desktop (customtkinter) que é o "cockpit" do PC:

  * Chat com o THOR (Claude Code) direto do PC, pela MESMA ponte MQTT do celular.
    Quando chega um PLANO, aparecem os botões Confirmar / Cancelar.
  * Duas luzes de status: Broker (conexão desta janela) e Daemon (ponte viva no PC).
  * Painel do daemon: log ao vivo (bridge/logs/bridge.log) + Iniciar / Reiniciar / Parar.
  * Campo opcional de projeto para mirar uma pasta do workspace.

Reaproveita as credenciais de bridge/.env (fonte única). Rode com a venv do bridge:
    bridge\\.venv\\Scripts\\python.exe control_center\\jarvis_control.py
(ou use control_center\\run_control.cmd)
"""

from __future__ import annotations

import json
import ssl
import subprocess
import threading
import uuid
from pathlib import Path

from dotenv import dotenv_values

# Confia na loja de certificados do Windows (TLS atrás de proxy/antivírus).
try:
    import truststore

    truststore.inject_into_ssl()
except Exception:  # noqa: BLE001 - opcional
    pass

import customtkinter as ctk
import paho.mqtt.client as mqtt
from paho.mqtt.enums import CallbackAPIVersion

from voz_pc import VozPC

# --------------------------------------------------------------------------- #
# Caminhos e configuração
# --------------------------------------------------------------------------- #
SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parent
BRIDGE_DIR = PROJECT_ROOT / "bridge"
ENV_PATH = BRIDGE_DIR / ".env"
LOG_PATH = BRIDGE_DIR / "logs" / "bridge.log"
# Modelo de voz em português — reaproveita o do app Android (offline, local).
MODEL_PT = PROJECT_ROOT / "app" / "src" / "main" / "assets" / "model-pt"

# No Windows, roda o powershell SEM abrir uma janela de console (senão pisca um
# prompt a cada checagem). getattr para não quebrar fora do Windows.
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)

ARC = "#29B6F6"       # azul "arc reactor"
ARC_DIM = "#37474F"   # azul apagado
OK = "#43A047"        # verde (conectado)
OFF = "#B0442F"       # vermelho (desconectado)
BG = "#0E1419"
PANEL = "#161E26"


def carregar_cfg() -> dict:
    cfg = dotenv_values(ENV_PATH)
    host = (cfg.get("MQTT_HOST") or "").strip()
    use_tls = (cfg.get("MQTT_TLS", "true") or "true").lower() not in ("0", "false", "no")
    port = int(cfg.get("MQTT_PORT") or (8883 if use_tls else 1883))
    prefix = (cfg.get("TOPIC_PREFIX") or "jarvis").rstrip("/")
    return {
        "host": host,
        "port": port,
        "user": cfg.get("MQTT_USER", "") or "",
        "password": cfg.get("MQTT_PASS", "") or "",
        "tls": use_tls,
        "prefix": prefix,
        "default_project": (cfg.get("DEFAULT_PROJECT") or "").strip(),
    }


# --------------------------------------------------------------------------- #
# Aplicação
# --------------------------------------------------------------------------- #
class ControlCenter(ctk.CTk):
    def __init__(self) -> None:
        super().__init__()
        self.cfg = carregar_cfg()
        self.prefix = self.cfg["prefix"]
        self.pending_id: str | None = None
        self.broker_ok = False
        self.client: mqtt.Client | None = None
        self._log_pos = 0
        self._parar = False
        self.falar_ativo = True   # eu respondo em voz alta (botão 🔊)
        self.voz: VozPC | None = None

        self.title("J.A.R.V.I.S. — Central de Controle")
        self.geometry("980x680")
        self.minsize(820, 560)
        ctk.set_appearance_mode("dark")
        self.configure(fg_color=BG)

        self._montar_ui()
        self._iniciar_voz()
        self._conectar_mqtt()
        self._iniciar_tail_log()
        self._agendar_checagem_daemon()
        self.protocol("WM_DELETE_WINDOW", self._ao_fechar)

    # ----- UI --------------------------------------------------------------- #
    def _montar_ui(self) -> None:
        self.grid_columnconfigure(0, weight=3)
        self.grid_columnconfigure(1, weight=2)
        self.grid_rowconfigure(1, weight=1)

        # Cabeçalho com título e as duas luzes.
        header = ctk.CTkFrame(self, fg_color=PANEL, corner_radius=0)
        header.grid(row=0, column=0, columnspan=2, sticky="ew")
        header.grid_columnconfigure(0, weight=1)
        ctk.CTkLabel(
            header, text="  J.A.R.V.I.S.", text_color=ARC,
            font=ctk.CTkFont(size=20, weight="bold"),
        ).grid(row=0, column=0, sticky="w", padx=8, pady=10)
        self.lamp_broker = self._lamp(header, "Broker")
        self.lamp_broker.grid(row=0, column=1, padx=6)
        self.lamp_daemon = self._lamp(header, "Daemon")
        self.lamp_daemon.grid(row=0, column=2, padx=(6, 14))

        # ----- Coluna esquerda: chat com o THOR ----------------------------- #
        left = ctk.CTkFrame(self, fg_color=BG)
        left.grid(row=1, column=0, sticky="nsew", padx=(10, 5), pady=10)
        left.grid_rowconfigure(1, weight=1)
        left.grid_columnconfigure(0, weight=1)

        proj = ctk.CTkFrame(left, fg_color="transparent")
        proj.grid(row=0, column=0, sticky="ew", pady=(0, 6))
        proj.grid_columnconfigure(1, weight=1)
        ctk.CTkLabel(proj, text="Projeto:", text_color="#90A4AE").grid(row=0, column=0, padx=(2, 6))
        self.entry_proj = ctk.CTkEntry(proj, placeholder_text=self.cfg["default_project"] or "(raiz do workspace)")
        self.entry_proj.grid(row=0, column=1, sticky="ew")
        self.chk_novo = ctk.CTkCheckBox(proj, text="novo", text_color="#90A4AE", fg_color=ARC)
        self.chk_novo.grid(row=0, column=2, padx=8)

        self.transcript = ctk.CTkTextbox(left, fg_color=PANEL, text_color="#ECEFF1", wrap="word")
        self.transcript.grid(row=1, column=0, sticky="nsew")
        self.transcript.configure(state="disabled")

        # Faixa do plano (Confirmar/Cancelar), escondida até chegar um plano.
        self.plan_frame = ctk.CTkFrame(left, fg_color="transparent")
        self.plan_frame.grid(row=2, column=0, sticky="ew", pady=4)
        self.btn_sim = ctk.CTkButton(
            self.plan_frame, text="✓ Confirmar", fg_color=OK, hover_color="#2E7D32",
            command=lambda: self._confirmar(True),
        )
        self.btn_nao = ctk.CTkButton(
            self.plan_frame, text="✗ Cancelar", fg_color=OFF, hover_color="#8E2F20",
            command=lambda: self._confirmar(False),
        )
        self._mostrar_plano(False)

        entrada = ctk.CTkFrame(left, fg_color="transparent")
        entrada.grid(row=3, column=0, sticky="ew")
        entrada.grid_columnconfigure(0, weight=1)
        self.entry_msg = ctk.CTkEntry(entrada, placeholder_text="Fale comigo, Senhor…")
        self.entry_msg.grid(row=0, column=0, sticky="ew", padx=(0, 8))
        self.entry_msg.bind("<Return>", lambda _e: self._enviar())
        # 🎤 falar (apertar p/ falar: clique inicia, fala encerra sozinha ou 2º clique)
        self.btn_mic = ctk.CTkButton(entrada, text="🎤", width=46, fg_color=PANEL,
                                     hover_color="#1E2A33", command=self._toggle_mic)
        self.btn_mic.grid(row=0, column=1, padx=(0, 6))
        # 🔊 ligar/desligar minha resposta em voz
        self.btn_falar = ctk.CTkButton(entrada, text="🔊", width=46, fg_color=ARC_DIM,
                                       hover_color="#1E2A33", command=self._toggle_falar)
        self.btn_falar.grid(row=0, column=2, padx=(0, 6))
        ctk.CTkButton(entrada, text="Enviar", width=90, fg_color=ARC, hover_color="#0288D1",
                      command=self._enviar).grid(row=0, column=3)

        # ----- Coluna direita: painel do daemon ----------------------------- #
        right = ctk.CTkFrame(self, fg_color=BG)
        right.grid(row=1, column=1, sticky="nsew", padx=(5, 10), pady=10)
        right.grid_rowconfigure(2, weight=1)
        right.grid_columnconfigure(0, weight=1)

        ctk.CTkLabel(right, text="Daemon da ponte", text_color=ARC,
                     font=ctk.CTkFont(size=14, weight="bold")).grid(row=0, column=0, sticky="w")
        botoes = ctk.CTkFrame(right, fg_color="transparent")
        botoes.grid(row=1, column=0, sticky="ew", pady=6)
        ctk.CTkButton(botoes, text="Iniciar", width=84, fg_color=OK, hover_color="#2E7D32",
                      command=lambda: self._daemon("iniciar")).pack(side="left", padx=2)
        ctk.CTkButton(botoes, text="Reiniciar", width=84, fg_color=ARC, hover_color="#0288D1",
                      command=lambda: self._daemon("reiniciar")).pack(side="left", padx=2)
        ctk.CTkButton(botoes, text="Parar", width=84, fg_color=OFF, hover_color="#8E2F20",
                      command=lambda: self._daemon("parar")).pack(side="left", padx=2)
        self.logbox = ctk.CTkTextbox(right, fg_color="#0A0F13", text_color="#9CCC65",
                                     wrap="none", font=ctk.CTkFont(family="Consolas", size=11))
        self.logbox.grid(row=2, column=0, sticky="nsew")
        self.logbox.configure(state="disabled")

    def _lamp(self, parent, texto: str) -> ctk.CTkLabel:
        return ctk.CTkLabel(parent, text=f"● {texto}", text_color=OFF,
                            font=ctk.CTkFont(size=13, weight="bold"))

    def _set_lamp(self, lamp: ctk.CTkLabel, ok: bool, texto: str) -> None:
        lamp.configure(text=f"● {texto}", text_color=OK if ok else OFF)

    def _mostrar_plano(self, visivel: bool) -> None:
        if visivel:
            self.btn_sim.pack(side="left", expand=True, fill="x", padx=(0, 4))
            self.btn_nao.pack(side="left", expand=True, fill="x", padx=(4, 0))
        else:
            self.btn_sim.pack_forget()
            self.btn_nao.pack_forget()

    def _escrever(self, quem: str, texto: str) -> None:
        self.transcript.configure(state="normal")
        self.transcript.insert("end", f"{quem}: {texto}\n\n")
        self.transcript.see("end")
        self.transcript.configure(state="disabled")

    def _status(self, texto: str) -> None:
        self._escrever("· status", texto)

    # ----- MQTT ------------------------------------------------------------- #
    def _conectar_mqtt(self) -> None:
        if not self.cfg["host"]:
            self._escrever("· erro", f"MQTT_HOST vazio em {ENV_PATH}. Configure o bridge/.env.")
            return
        c = mqtt.Client(CallbackAPIVersion.VERSION2, client_id=f"jarvis-ctl-{uuid.uuid4().hex[:8]}")
        if self.cfg["user"]:
            c.username_pw_set(self.cfg["user"], self.cfg["password"])
        if self.cfg["tls"]:
            c.tls_set_context(ssl.create_default_context())
        c.reconnect_delay_set(min_delay=1, max_delay=30)
        c.on_connect = self._on_connect
        c.on_disconnect = self._on_disconnect
        c.on_message = self._on_message
        self.client = c
        try:
            c.connect(self.cfg["host"], self.cfg["port"], keepalive=45)
            c.loop_start()
        except Exception as e:  # noqa: BLE001
            self.after(0, lambda: self._escrever("· erro", f"Falha ao conectar no broker: {e}"))

    def _on_connect(self, client, userdata, flags, reason_code, properties) -> None:
        ok = reason_code == 0
        self.broker_ok = ok
        if ok:
            client.subscribe(f"{self.prefix}/replies", 1)
        self.after(0, lambda: self._set_lamp(self.lamp_broker, ok, "Broker"))

    def _on_disconnect(self, client, userdata, flags, reason_code, properties) -> None:
        self.broker_ok = False
        self.after(0, lambda: self._set_lamp(self.lamp_broker, False, "Broker"))

    def _on_message(self, client, userdata, msg) -> None:
        try:
            o = json.loads(msg.payload.decode("utf-8"))
        except Exception:  # noqa: BLE001
            return
        self.after(0, lambda: self._tratar_reply(o))

    def _tratar_reply(self, o: dict) -> None:
        rid = o.get("id", "")
        # Só reage ao NOSSO pedido (ignora o tráfego do celular).
        if self.pending_id and rid and rid != self.pending_id:
            return
        tipo = o.get("type", "")
        texto = o.get("text", "")
        if tipo == "status":
            self._status(texto)
        elif tipo == "plan":
            self._escrever("J.A.R.V.I.S.", texto)
            self._mostrar_plano(True)
            self._falar(texto)
        else:  # result | error
            self._escrever("J.A.R.V.I.S.", texto)
            self._mostrar_plano(False)
            self.pending_id = None
            self._falar(texto)

    def _enviar(self) -> None:
        texto = self.entry_msg.get().strip()
        if not texto or not self.client or not self.broker_ok:
            if not self.broker_ok:
                self._escrever("· erro", "Sem conexão com o broker, Senhor.")
            return
        self.entry_msg.delete(0, "end")
        self._escrever("Você", texto)
        rid = uuid.uuid4().hex[:12]
        self.pending_id = rid
        payload = {"id": rid, "text": texto}
        proj = self.entry_proj.get().strip()
        if proj:
            payload["project"] = proj
        if self.chk_novo.get():
            payload["new"] = True
        self.client.publish(f"{self.prefix}/requests", json.dumps(payload, ensure_ascii=False), qos=1)
        self._mostrar_plano(False)

    def _confirmar(self, ok: bool) -> None:
        if not self.client or not self.pending_id:
            return
        self.client.publish(
            f"{self.prefix}/confirm",
            json.dumps({"id": self.pending_id, "ok": ok}),
            qos=1,
        )
        self._mostrar_plano(False)
        self._status("Executando no computador…" if ok else "Cancelado, Senhor.")
        if not ok:
            self.pending_id = None

    # ----- Voz (ouvir Vosk + falar SAPI, tudo local) ------------------------ #
    def _iniciar_voz(self) -> None:
        self.voz = VozPC(
            MODEL_PT,
            on_parcial=lambda t: self.after(0, lambda: self._voz_parcial(t)),
            on_final=lambda t: self.after(0, lambda: self._voz_final(t)),
            on_estado=lambda e: self.after(0, lambda: self._voz_estado(e)),
        )
        if not self.voz.stt_ok:
            self.btn_mic.configure(state="disabled")
        if not self.voz.tts_ok:
            self.btn_falar.configure(state="disabled")
        self._pintar_falar()

    def _toggle_mic(self) -> None:
        if not self.voz or not self.voz.stt_ok:
            self._escrever("· erro", "Voz para ouvir indisponível, Senhor.")
            return
        if self.voz.esta_ouvindo:
            self.voz.parar()           # 2º clique encerra e envia o que ouviu
            return
        self.voz.parar_fala()          # não me ouço (sem eco)
        self.entry_msg.delete(0, "end")
        self.voz.ouvir()

    def _toggle_falar(self) -> None:
        self.falar_ativo = not self.falar_ativo
        if not self.falar_ativo and self.voz:
            self.voz.parar_fala()
        self._pintar_falar()

    def _pintar_falar(self) -> None:
        self.btn_falar.configure(fg_color=ARC if self.falar_ativo else ARC_DIM)

    def _falar(self, texto: str) -> None:
        if self.falar_ativo and self.voz and self.voz.tts_ok:
            self.voz.falar(texto)

    def _voz_parcial(self, texto: str) -> None:
        self.entry_msg.delete(0, "end")
        self.entry_msg.insert(0, texto)

    def _voz_final(self, texto: str) -> None:
        self.btn_mic.configure(text="🎤", fg_color=PANEL)
        if texto.strip():
            self.entry_msg.delete(0, "end")
            self.entry_msg.insert(0, texto.strip())
            self._enviar()             # envia automaticamente ao terminar a fala

    def _voz_estado(self, estado: str) -> None:
        if estado == "carregando":
            self.btn_mic.configure(text="⏳")
        elif estado == "pronto":
            self.btn_mic.configure(text="🎤", fg_color=PANEL, state="normal")
        elif estado == "ouvindo":
            self.btn_mic.configure(text="●", fg_color=OFF)
        elif estado in ("parado", "fim_fala"):
            self.btn_mic.configure(text="🎤", fg_color=PANEL)
        elif estado == "falando":
            pass
        elif estado == "indisponivel":
            self._escrever("· erro", "Voz para ouvir indisponível, Senhor.")
        elif estado.startswith("erro:"):
            self._escrever("· erro", f"Falha na voz: {estado.split(':', 1)[1]}")

    # ----- Daemon (log ao vivo + controles) --------------------------------- #
    def _iniciar_tail_log(self) -> None:
        threading.Thread(target=self._tail_loop, daemon=True).start()

    def _tail_loop(self) -> None:
        import time
        while not self._parar:
            try:
                if LOG_PATH.exists():
                    with open(LOG_PATH, "r", encoding="utf-8", errors="replace") as f:
                        f.seek(self._log_pos)
                        novo = f.read()
                        self._log_pos = f.tell()
                    if novo:
                        self.after(0, lambda t=novo: self._anexar_log(t))
            except Exception:  # noqa: BLE001
                pass
            time.sleep(1.0)

    def _anexar_log(self, texto: str) -> None:
        self.logbox.configure(state="normal")
        self.logbox.insert("end", texto)
        # Mantém o log enxuto (últimas ~600 linhas).
        linhas = int(self.logbox.index("end-1c").split(".")[0])
        if linhas > 600:
            self.logbox.delete("1.0", f"{linhas - 600}.0")
        self.logbox.see("end")
        self.logbox.configure(state="disabled")

    def _daemon(self, acao: str) -> None:
        cmds = {
            "iniciar": "Start-ScheduledTask -TaskName JarvisBridge",
            "parar": (
                "try { Stop-ScheduledTask -TaskName JarvisBridge } catch {}; "
                "Get-NetTCPConnection -RemotePort 8883 -ErrorAction SilentlyContinue | "
                "ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }"
            ),
            # Reinício leve: mata o python do daemon; o run_bridge.cmd relança em ~10s.
            "reiniciar": (
                "Get-NetTCPConnection -RemotePort 8883 -ErrorAction SilentlyContinue | "
                "ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }"
            ),
        }
        ps = cmds.get(acao)
        if not ps:
            return
        self._status(f"Daemon: {acao}…")

        def run():
            try:
                subprocess.run(
                    ["powershell", "-NoProfile", "-NonInteractive", "-Command", ps],
                    capture_output=True, text=True, timeout=30, creationflags=NO_WINDOW,
                )
            except Exception as e:  # noqa: BLE001
                self.after(0, lambda: self._status(f"Falha ao {acao} o daemon: {e}"))

        threading.Thread(target=run, daemon=True).start()

    def _agendar_checagem_daemon(self) -> None:
        if self._parar:
            return
        threading.Thread(target=self._checar_daemon, daemon=True).start()
        self.after(6000, self._agendar_checagem_daemon)

    def _checar_daemon(self) -> None:
        vivo = False
        try:
            r = subprocess.run(
                ["powershell", "-NoProfile", "-NonInteractive", "-Command",
                 "if (Get-NetTCPConnection -RemotePort 8883 -ErrorAction SilentlyContinue) { 'UP' } else { 'DOWN' }"],
                capture_output=True, text=True, timeout=10, creationflags=NO_WINDOW,
            )
            vivo = "UP" in (r.stdout or "")
        except Exception:  # noqa: BLE001
            vivo = False
        self.after(0, lambda: self._set_lamp(self.lamp_daemon, vivo, "Daemon"))

    def _ao_fechar(self) -> None:
        self._parar = True
        if self.voz:
            self.voz.parar()
            self.voz.parar_fala()
        try:
            if self.client:
                self.client.loop_stop()
                self.client.disconnect()
        except Exception:  # noqa: BLE001
            pass
        self.destroy()


if __name__ == "__main__":
    ControlCenter().mainloop()
