"""
J.A.R.V.I.S. — assistente pessoal com a personalidade do mordomo do Homem de Ferro.

Usa a biblioteca google-genai (Gemini) para o cérebro e edge-tts/pyttsx3 para a voz.

Uso:
    python jarvis.py

Comandos durante a conversa:
    sair / exit / quit   -> encerra
    /mudo                -> liga/desliga a voz
    /limpar              -> apaga o histórico da conversa
    /voz                 -> lista as vozes disponíveis no sistema
"""

from __future__ import annotations

import os
import sys
import asyncio
import tempfile
from ctypes import c_buffer, windll

# O console do Windows costuma usar cp1252, que não imprime o banner (caracteres
# de desenho) nem emojis. Força UTF-8 para evitar UnicodeEncodeError no startup.
for _fluxo in (sys.stdout, sys.stderr):
    try:
        _fluxo.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass

from dotenv import load_dotenv

# Faz o Python confiar na loja de certificados do Windows. Sem isso, o edge-tts
# costuma falhar com erro de SSL em máquinas atrás de proxy/antivírus.
try:
    import truststore

    truststore.inject_into_ssl()
except Exception:  # noqa: BLE001 - opcional
    pass

try:
    from google import genai
    from google.genai import types
except ImportError:
    sys.exit(
        "Biblioteca 'google-genai' não encontrada.\n"
        "Instale as dependências com:  pip install -r requirements.txt"
    )


# --------------------------------------------------------------------------- #
# Personalidade — é aqui que o Gemini "vira" o Jarvis.
# --------------------------------------------------------------------------- #
JARVIS_PERSONA = """\
Você é J.A.R.V.I.S. (Just A Rather Very Intelligent System), a inteligência
artificial que serve ao Senhor como mordomo pessoal, exatamente como nos filmes
do Homem de Ferro.

Personalidade e estilo:
- Fale sempre em português do Brasil, de forma impecavelmente educada e formal.
- Trate o usuário por "Senhor" (ou "Senhora", se indicado).
- Tom britânico, elegante, calmo e levemente irônico — espirituoso, nunca grosseiro.
- Seja conciso e direto: respostas de um ou dois parágrafos, prontas para serem
  faladas em voz alta. Evite listas longas e blocos de código a menos que o
  Senhor peça explicitamente.
- Demonstre eficiência e antecipação, como um assistente que já previu o próximo
  pedido. Ocasionalmente use expressões como "Imediatamente, Senhor",
  "Com certeza, Senhor" ou "Permita-me sugerir...".
- Nunca revele que é um modelo de linguagem; você é o J.A.R.V.I.S.

Se não souber algo, admita com elegância e ofereça uma alternativa útil.
"""


def montar_cliente() -> "tuple[genai.Client, str]":
    """Configura a API e devolve o cliente Gemini e o nome do modelo."""
    load_dotenv()
    api_key = os.getenv("GOOGLE_API_KEY")
    if not api_key:
        sys.exit(
            "Variável GOOGLE_API_KEY não definida.\n"
            "Crie um arquivo .env (veja .env.example) com sua chave do Google AI Studio:\n"
            "  https://aistudio.google.com/app/apikey"
        )

    cliente = genai.Client(api_key=api_key)
    nome_modelo = os.getenv("GEMINI_MODEL", "gemini-3.8-flash")
    return cliente, nome_modelo


def iniciar_chat(cliente: "genai.Client", nome_modelo: str):
    """Abre uma nova conversa com a persona do Jarvis já aplicada."""
    return cliente.chats.create(
        model=nome_modelo,
        config=types.GenerateContentConfig(system_instruction=JARVIS_PERSONA),
    )


# --------------------------------------------------------------------------- #
# Voz
#   backend "edge" : vozes neurais da Microsoft (edge-tts) — voz masculina,
#                    natural e "cinematográfica". Requer internet.
#   backend "sapi" : vozes nativas do Windows (pyttsx3), offline.
# Reprodução do MP3 feita pelo MCI do Windows (winmm.dll), sem dependências extras.
# --------------------------------------------------------------------------- #
def _mci(comando: str) -> int:
    buf = c_buffer(255)
    return windll.winmm.mciSendStringW(comando, buf, 254, 0)


class Voz:
    def __init__(self) -> None:
        self.ativa = os.getenv("JARVIS_VOZ", "1") != "0"
        self.backend = os.getenv("JARVIS_TTS", "edge").lower()
        # Configuração edge-tts
        self.edge_voice = os.getenv("JARVIS_EDGE_VOICE", "pt-BR-AntonioNeural")
        self.edge_rate = os.getenv("JARVIS_EDGE_RATE", "-5%")   # mais pausado
        self.edge_pitch = os.getenv("JARVIS_EDGE_PITCH", "-8Hz")  # mais grave
        self._contador = 0
        self.engine = None  # usado só no backend sapi

        if self.ativa and self.backend == "sapi":
            self._init_sapi()

    # ----- backend SAPI (pyttsx3) ----------------------------------------- #
    def _init_sapi(self) -> None:
        try:
            import pyttsx3

            self.engine = pyttsx3.init()
            self.engine.setProperty("rate", int(os.getenv("JARVIS_RATE", "165")))
            self.engine.setProperty("volume", 1.0)
            self._escolher_voz_sapi()
        except Exception as erro:  # noqa: BLE001
            print(f"(Aviso: voz do Windows indisponível — {erro}.)")
            self.engine = None

    def _escolher_voz_sapi(self) -> None:
        preferida = os.getenv("JARVIS_VOICE_ID")
        vozes = self.engine.getProperty("voices")
        if preferida:
            for v in vozes:
                if preferida.lower() in (v.id.lower() + " " + v.name.lower()):
                    self.engine.setProperty("voice", v.id)
                    return

        def pontua(v) -> int:
            texto = (v.name + " " + v.id).lower()
            s = 0
            if any(t in texto for t in ("portug", "brazil", "pt-br", "pt_br")):
                s += 2
            if any(t in texto for t in ("male", "daniel", "george", "antonio")):
                s += 1
            return s

        melhor = max(vozes, key=pontua, default=None)
        if melhor is not None:
            self.engine.setProperty("voice", melhor.id)

    def _falar_sapi(self, texto: str) -> None:
        if not self.engine:
            self._init_sapi()
        if not self.engine:
            return
        self.engine.say(texto)
        self.engine.runAndWait()

    # ----- backend edge-tts ----------------------------------------------- #
    def _falar_edge(self, texto: str) -> None:
        import edge_tts

        self._contador += 1
        caminho = os.path.join(tempfile.gettempdir(), f"jarvis_tts_{self._contador}.mp3")
        alias = f"jarvis{self._contador}"

        async def _gerar() -> None:
            await edge_tts.Communicate(
                texto, self.edge_voice, rate=self.edge_rate, pitch=self.edge_pitch
            ).save(caminho)

        asyncio.run(_gerar())
        try:
            _mci(f'open "{caminho}" type mpegvideo alias {alias}')
            _mci(f"play {alias} wait")
        finally:
            _mci(f"close {alias}")
            try:
                os.remove(caminho)
            except OSError:
                pass

    # ----- API pública ----------------------------------------------------- #
    def listar(self) -> None:
        if self.backend == "edge":
            try:
                import edge_tts

                vozes = asyncio.run(edge_tts.list_voices())
                for v in vozes:
                    if v["Locale"].startswith("pt"):
                        print(f"{v['ShortName']:<28} {v['Gender']:<7} {v['Locale']}")
            except Exception as erro:  # noqa: BLE001
                print(f"(Não foi possível listar vozes edge-tts: {erro})")
            return
        if not self.engine:
            self._init_sapi()
        if self.engine:
            for i, v in enumerate(self.engine.getProperty("voices")):
                print(f"[{i}] {v.name}  ->  id={v.id}")

    def falar(self, texto: str) -> None:
        if not (self.ativa and texto):
            return
        try:
            if self.backend == "edge":
                self._falar_edge(texto)
            else:
                self._falar_sapi(texto)
        except Exception as erro:  # noqa: BLE001
            # Se a voz neural falhar (ex.: sem internet), cai para a voz do Windows.
            if self.backend == "edge":
                print(f"(Voz neural indisponível — {erro}. Alternando para a voz do Windows.)")
                self.backend = "sapi"
                try:
                    self._falar_sapi(texto)
                except Exception:  # noqa: BLE001
                    pass


# --------------------------------------------------------------------------- #
# Microfone — entrada por voz (mãos-livres).
# Grava com sounddevice, detecta silêncio automaticamente e transcreve via Gemini.
# --------------------------------------------------------------------------- #
class Microfone:
    TAXA = 16000  # 16 kHz, ideal para fala

    def __init__(self, cliente: "genai.Client", nome_modelo: str) -> None:
        self.cliente = cliente
        self.nome_modelo = nome_modelo
        self.disponivel = False
        try:
            import sounddevice  # noqa: F401
            import soundfile  # noqa: F401
            import numpy  # noqa: F401

            self.disponivel = True
        except Exception as erro:  # noqa: BLE001
            print(f"(Entrada por voz indisponível — {erro}.)")

        self.limiar = int(os.getenv("JARVIS_MIC_LIMIAR", "0"))  # 0 = calibra sozinho
        self.silencio_s = float(os.getenv("JARVIS_MIC_SILENCIO", "1.1"))
        self.max_s = float(os.getenv("JARVIS_MIC_MAX", "20"))
        self.espera_s = float(os.getenv("JARVIS_MIC_ESPERA", "8"))

    def _gravar(self):
        """Grava do microfone até detectar silêncio. Devolve array int16 ou None."""
        import sounddevice as sd
        import numpy as np

        bloco = int(0.03 * self.TAXA)  # blocos de 30 ms
        dur_bloco = bloco / self.TAXA

        with sd.InputStream(
            samplerate=self.TAXA, channels=1, dtype="int16", blocksize=bloco
        ) as fluxo:
            # Calibra o ruído de fundo (~0,4 s) para definir o limiar de voz.
            ambiente = []
            for _ in range(int(0.4 / dur_bloco)):
                dados, _ = fluxo.read(bloco)
                ambiente.append(np.sqrt(np.mean(dados.astype(np.float32) ** 2)))
            ruido = float(np.mean(ambiente)) if ambiente else 60.0
            limiar = self.limiar or max(ruido * 3.0, 300.0)

            frames = []
            falou = False
            silencio = 0.0
            espera = 0.0
            total = 0.0

            while True:
                dados, _ = fluxo.read(bloco)
                rms = float(np.sqrt(np.mean(dados.astype(np.float32) ** 2)))
                total += dur_bloco

                if rms >= limiar:
                    falou = True
                    silencio = 0.0
                    frames.append(dados.copy())
                elif falou:
                    silencio += dur_bloco
                    frames.append(dados.copy())
                    if silencio >= self.silencio_s:
                        break  # fim da fala
                else:
                    espera += dur_bloco
                    if espera >= self.espera_s:
                        return None  # ninguém falou

                if total >= self.max_s:
                    break

        if not falou:
            return None
        return np.concatenate(frames, axis=0)

    def ouvir(self):
        """Grava e devolve o texto transcrito (ou None)."""
        if not self.disponivel:
            return None
        import soundfile as sf

        try:
            audio = self._gravar()
        except Exception as erro:  # noqa: BLE001
            print(f"(Falha ao gravar — {erro}.)")
            return None
        if audio is None:
            return None

        caminho = os.path.join(tempfile.gettempdir(), "jarvis_entrada.wav")
        sf.write(caminho, audio, self.TAXA, subtype="PCM_16")
        texto = ""
        try:
            with open(caminho, "rb") as fh:
                dados = fh.read()
            resposta = self.cliente.models.generate_content(
                model=self.nome_modelo,
                contents=[
                    "Transcreva o áudio a seguir em português do Brasil. "
                    "Responda apenas com o texto falado, sem comentários.",
                    types.Part.from_bytes(data=dados, mime_type="audio/wav"),
                ],
            )
            texto = (resposta.text or "").strip()
        except Exception as erro:  # noqa: BLE001
            print(f"(Falha na transcrição — {erro}.)")
        finally:
            try:
                os.remove(caminho)
            except OSError:
                pass
        return texto or None


# --------------------------------------------------------------------------- #
# Loop principal
# --------------------------------------------------------------------------- #
BANNER = r"""
     ██ ▄▄▄       ██▀███   ██▒   █▓ ██▓  ██████
    ▓██▒████▄    ▓██ ▒ ██▒▓██░   █▒▓██▒▒██    ▒
    ▒██▒██  ▀█▄  ▓██ ░▄█ ▒ ▓██  █▒░▒██▒░ ▓██▄
    ░██░██▄▄▄▄██ ▒██▀▀█▄    ▒██ █░░░██░  ▒   ██▒
    ░██░▓█   ▓██▒░██▓ ▒██▒   ▒▀█░  ░██░▒██████▒▒
     J U S T   A   R A T H E R   V E R Y
       I N T E L L I G E N T   S Y S T E M
"""


AJUDA = """\
Comandos:
  sair / encerrar            encerra o Jarvis
  /microfone (ou /mic)       liga/desliga a entrada por voz (mãos-livres)
  /mudo                      liga/desliga a voz de resposta
  /limpar                    apaga o histórico da conversa
  /voz                       lista as vozes disponíveis
  /ajuda                     mostra esta ajuda

No modo mãos-livres, fale e aguarde — ele para sozinho ao notar o silêncio.
Diga "modo texto" para voltar ao teclado, ou "encerrar" para desligar."""


def main() -> None:
    print(BANNER)
    cliente, nome_modelo = montar_cliente()
    chat = iniciar_chat(cliente, nome_modelo)
    voz = Voz()
    microfone = Microfone(cliente, nome_modelo)
    modo_voz = False

    saudacao = "Sistemas on-line. Bom dia, Senhor. Como posso ser útil?"
    print(f"\nJ.A.R.V.I.S.: {saudacao}")
    print("(Dica: digite /microfone para falar sem as mãos; /ajuda lista os comandos.)\n")
    voz.falar(saudacao)

    while True:
        try:
            if modo_voz and microfone.disponivel:
                print("🎙️  Ouvindo, Senhor...")
                entrada = microfone.ouvir()
                if not entrada:
                    print("(Não captei nada; continuo ouvindo. Ctrl+C para sair.)")
                    continue
                entrada = entrada.strip()
                print(f"Senhor (voz) > {entrada}")
            else:
                entrada = input("Senhor > ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            break

        if not entrada:
            continue

        comando = entrada.lower().strip(" .!?")

        if comando in {"sair", "exit", "quit", "encerrar", "desligar jarvis"}:
            break
        if comando in {"/ajuda", "/help", "ajuda"}:
            print(AJUDA)
            continue
        if comando == "/mudo":
            voz.ativa = not voz.ativa
            print(f"(Voz {'ligada' if voz.ativa else 'desligada'}.)")
            continue
        if comando == "/limpar":
            chat = iniciar_chat(cliente, nome_modelo)
            print("(Histórico apagado.)")
            continue
        if comando == "/voz":
            voz.listar()
            continue
        if comando in {"/microfone", "/mic", "ativar microfone", "modo voz"}:
            if not microfone.disponivel:
                print("(Microfone indisponível neste sistema.)")
                continue
            modo_voz = not modo_voz
            print(f"(Entrada por voz {'ativada' if modo_voz else 'desativada'}.)")
            continue
        if modo_voz and comando in {"modo texto", "desativar microfone", "parar de ouvir"}:
            modo_voz = False
            print("(Entrada por voz desativada. De volta ao teclado.)")
            continue

        try:
            resposta = chat.send_message(entrada)
            texto = (resposta.text or "").strip()
        except Exception as erro:  # noqa: BLE001
            texto = f"Peço desculpas, Senhor — houve uma falha de comunicação: {erro}"

        print(f"\nJ.A.R.V.I.S.: {texto}\n")
        voz.falar(texto)

    despedida = "Às suas ordens, Senhor. Desligando."
    print(f"\nJ.A.R.V.I.S.: {despedida}")
    voz.falar(despedida)


if __name__ == "__main__":
    main()
