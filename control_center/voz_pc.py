"""
J.A.R.V.I.S. — Voz no PC (100% local, sem serviço externo).

  * OUVIR  : Vosk (reaproveita o modelo em português do app Android) + sounddevice.
  * FALAR  : SAPI do Windows via pyttsx3 (voz "Microsoft Maria", pt-BR).

Projetado para "apertar para falar": `ouvir()` abre o microfone; a fala é
transcrita ao vivo (on_parcial) e, ao fim de uma frase — ou quando `parar()` é
chamado — o texto final chega em on_final. Enquanto eu falo (TTS), o microfone
não é aberto (evita eco, como corrigimos no celular).

Degrada com elegância: se as bibliotecas de voz não estiverem instaladas, o
import de `VozPC` ainda funciona e `disponivel` fica False — a Central de
Controle segue em modo texto.
"""
from __future__ import annotations

import json
import os
import queue
import subprocess
import tempfile
import threading
from pathlib import Path
from typing import Callable, Optional

os.environ.setdefault("VOSK_LOG_LEVEL", "-1")  # silencia o log do Kaldi

# Importes opcionais — se faltarem, a Central continua só em texto.
try:
    import sounddevice as sd
    from vosk import KaldiRecognizer, Model

    _STT_OK = True
except Exception:  # noqa: BLE001
    _STT_OK = False

try:
    import pyttsx3

    _TTS_OK = True
except Exception:  # noqa: BLE001
    _TTS_OK = False

try:
    import winsound  # stdlib (Windows): toca o WAV do Piper, com SND_PURGE p/ parar

    _WINSOUND = True
except Exception:  # noqa: BLE001
    _WINSOUND = False

TAXA = 16000          # Hz exigidos pelo modelo Vosk
BLOCO = 4000          # amostras por leitura (~0,25 s)
SILENCIO_FIM = 1.2    # s de silêncio após fala para encerrar sozinho

# Voz MASCULINA local (Piper, offline): preferida sobre o SAPI (Maria, feminina).
_DIR = Path(__file__).resolve().parent
PIPER_EXE = _DIR / "piper" / "piper.exe"
PIPER_VOICE = _DIR / "piper" / "pt_BR-faber-medium.onnx"
_PIPER_OK = _WINSOUND and PIPER_EXE.exists() and PIPER_VOICE.exists()
PIPER_RATE = "1.0"    # comprimento (1.0 normal; >1 mais devagar)


class VozPC:
    """Ponte de voz local: ouvir (Vosk) e falar (SAPI)."""

    def __init__(
        self,
        model_path: str | os.PathLike,
        on_parcial: Optional[Callable[[str], None]] = None,
        on_final: Optional[Callable[[str], None]] = None,
        on_estado: Optional[Callable[[str], None]] = None,
    ) -> None:
        self.model_path = Path(model_path)
        self.on_parcial = on_parcial
        self.on_final = on_final
        self.on_estado = on_estado

        self._model: Optional["Model"] = None
        self._ouvindo = False
        self._falando = threading.Event()
        self._eng_atual = None  # engine pyttsx3 em uso (p/ interromper)
        self._piper_proc: Optional[subprocess.Popen] = None  # piper em geração
        self._thread_ouvir: Optional[threading.Thread] = None

        self.stt_ok = _STT_OK and self.model_path.exists()
        self.piper_ok = _PIPER_OK                 # voz masculina local
        self.tts_ok = _TTS_OK or self.piper_ok    # qualquer motor de fala
        if self.stt_ok:
            threading.Thread(target=self._carregar_modelo, daemon=True).start()

    # ----- estado ----------------------------------------------------------- #
    @property
    def disponivel(self) -> bool:
        return self.stt_ok or self.tts_ok

    @property
    def esta_falando(self) -> bool:
        return self._falando.is_set()

    @property
    def esta_ouvindo(self) -> bool:
        return self._ouvindo

    def _emit(self, estado: str) -> None:
        if self.on_estado:
            try:
                self.on_estado(estado)
            except Exception:  # noqa: BLE001
                pass

    # ----- modelo ----------------------------------------------------------- #
    def _carregar_modelo(self) -> None:
        self._emit("carregando")
        try:
            self._model = Model(str(self.model_path))
            self._emit("pronto")
        except Exception as e:  # noqa: BLE001
            self.stt_ok = False
            self._emit(f"erro:modelo:{e}")

    # ----- OUVIR (Vosk) ----------------------------------------------------- #
    def ouvir(self) -> bool:
        """Abre o microfone. Retorna False se não puder (falando/sem modelo)."""
        if not self.stt_ok or self._model is None:
            self._emit("indisponivel")
            return False
        if self._ouvindo or self.esta_falando:
            return False
        self._ouvindo = True
        self._thread_ouvir = threading.Thread(target=self._loop_ouvir, daemon=True)
        self._thread_ouvir.start()
        return True

    def parar(self) -> None:
        """Encerra a captação; o texto final sai por on_final."""
        self._ouvindo = False

    def _loop_ouvir(self) -> None:
        import time

        self._emit("ouvindo")
        rec = KaldiRecognizer(self._model, TAXA)
        rec.SetWords(False)
        partes: list[str] = []
        q: "queue.Queue[bytes]" = queue.Queue()

        def cb(indata, frames, tempo, status):  # noqa: ANN001
            q.put(bytes(indata))

        ultimo_fala = time.monotonic()
        houve_fala = False
        try:
            with sd.RawInputStream(
                samplerate=TAXA, blocksize=BLOCO, dtype="int16",
                channels=1, callback=cb,
            ):
                while self._ouvindo:
                    try:
                        data = q.get(timeout=0.3)
                    except queue.Empty:
                        continue
                    if rec.AcceptWaveform(data):
                        txt = json.loads(rec.Result()).get("text", "").strip()
                        if txt:
                            partes.append(txt)
                            houve_fala = True
                            # Frase concluída + silêncio -> encerra sozinho.
                            self._ouvindo = False
                    else:
                        parcial = json.loads(rec.PartialResult()).get("partial", "").strip()
                        if parcial:
                            houve_fala = True
                            ultimo_fala = time.monotonic()
                            vivo = (" ".join(partes + [parcial])).strip()
                            if self.on_parcial:
                                self.on_parcial(vivo)
                        elif houve_fala and (time.monotonic() - ultimo_fala) > SILENCIO_FIM:
                            self._ouvindo = False
        except Exception as e:  # noqa: BLE001
            self._emit(f"erro:mic:{e}")

        final = json.loads(rec.FinalResult()).get("text", "").strip()
        if final:
            partes.append(final)
        texto = " ".join(p for p in partes if p).strip()
        self._emit("parado")
        if self.on_final:
            self.on_final(texto)

    # ----- FALAR (Piper masculino, c/ SAPI de reserva) ---------------------- #
    def falar(self, texto: str, ao_terminar: Optional[Callable[[], None]] = None) -> None:
        """Fala o texto em voz alta (thread própria). Interrompe uma fala anterior."""
        if not self.tts_ok or not (texto or "").strip():
            if ao_terminar:
                ao_terminar()
            return
        self.parar_fala()
        alvo = self._falar_piper if self.piper_ok else self._falar_sapi
        threading.Thread(target=alvo, args=(texto, ao_terminar), daemon=True).start()

    def _falar_piper(self, texto: str, ao_terminar: Optional[Callable[[], None]]) -> None:
        """Voz masculina 'faber' (Piper, offline): gera um WAV e toca com winsound."""
        self._falando.set()
        self._emit("falando")
        wav: Optional[str] = None
        try:
            fd, wav = tempfile.mkstemp(suffix=".wav", prefix="jarvis_tts_")
            os.close(fd)
            proc = subprocess.Popen(
                [str(PIPER_EXE), "-m", str(PIPER_VOICE),
                 "-f", wav, "--length_scale", PIPER_RATE],
                stdin=subprocess.PIPE,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                cwd=str(PIPER_EXE.parent),
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            self._piper_proc = proc
            proc.communicate(input=(texto + "\n").encode("utf-8"))
            self._piper_proc = None
            # Só toca se não foi interrompido e o WAV saiu válido (>cabeçalho).
            if self._falando.is_set() and os.path.exists(wav) and os.path.getsize(wav) > 44:
                winsound.PlaySound(wav, winsound.SND_FILENAME)
        except Exception as e:  # noqa: BLE001
            self._emit(f"erro:tts:{e}")
        finally:
            self._piper_proc = None
            self._falando.clear()
            self._emit("fim_fala")
            if wav:
                try:
                    os.remove(wav)
                except Exception:  # noqa: BLE001
                    pass
            if ao_terminar:
                try:
                    ao_terminar()
                except Exception:  # noqa: BLE001
                    pass

    def _falar_sapi(self, texto: str, ao_terminar: Optional[Callable[[], None]]) -> None:
        self._falando.set()
        self._emit("falando")
        try:
            eng = pyttsx3.init()
            self._aplicar_voz_pt(eng)
            eng.setProperty("rate", 188)
            self._eng_atual = eng
            eng.say(texto)
            eng.runAndWait()
            try:
                eng.stop()
            except Exception:  # noqa: BLE001
                pass
        except Exception as e:  # noqa: BLE001
            self._emit(f"erro:tts:{e}")
        finally:
            self._eng_atual = None
            self._falando.clear()
            self._emit("fim_fala")
            if ao_terminar:
                try:
                    ao_terminar()
                except Exception:  # noqa: BLE001
                    pass

    def _aplicar_voz_pt(self, eng) -> None:  # noqa: ANN001
        try:
            for v in eng.getProperty("voices"):
                assinatura = f"{v.id} {v.name} {getattr(v, 'languages', '')}".lower()
                if "pt" in assinatura or "maria" in assinatura or "brazil" in assinatura:
                    eng.setProperty("voice", v.id)
                    return
        except Exception:  # noqa: BLE001
            pass

    def parar_fala(self) -> None:
        # Sinaliza interrupção e corta o áudio do Piper (se tocando).
        falava = self._falando.is_set()
        self._falando.clear()
        if _WINSOUND and falava:
            try:
                winsound.PlaySound(None, winsound.SND_PURGE)
            except Exception:  # noqa: BLE001
                pass
        proc = self._piper_proc
        if proc is not None:
            try:
                proc.kill()
            except Exception:  # noqa: BLE001
                pass
        eng = self._eng_atual
        if eng is not None:
            try:
                eng.stop()
            except Exception:  # noqa: BLE001
                pass
