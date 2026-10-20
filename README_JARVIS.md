    # J.A.R.V.I.S. — assistente pessoal com voz

Assistente com a personalidade do mordomo do Homem de Ferro, usando o **Gemini**
como cérebro. O projeto tem **duas versões**:

- **App Android nativo** (Kotlin + Jetpack Compose) — pasta `app/`. Voz e microfone
  nativos do Android; é a versão recomendada para o celular. Veja abaixo.
- **Versão desktop** (Python/CLI) — `jarvis.py`. Roda no Windows, com voz por
  `edge-tts`/SAPI. Documentada mais adiante.

---

## 📱 Versão Android

App nativo que conversa com o Gemini e fala em voz alta usando os recursos do
próprio Android:

- **`TextToSpeech`** (voz pt-BR, tom grave e pausado) para as respostas.
- **`SpeechRecognizer`** para a entrada por voz / modo mãos-livres.
- Interface de chat em Compose com visual "arc reactor" (azul sobre fundo escuro).
- A persona do Jarvis é a mesma da versão desktop.

### Pré-requisitos

- **Android Studio** (ou o SDK do Android instalado).
- **JDK 17** para o Gradle. O stack usado é **AGP 8.7.3 + Gradle 8.11.1**, que
  **não roda no Java 25** do JBR padrão do Android Studio. Em
  *Settings → Build, Execution, Deployment → Build Tools → Gradle*, defina o
  **Gradle JDK** como o `jdk-17` (há um em `C:\Program Files\Java\jdk-17`).
- `minSdk 26` (Android 8.0) · `compileSdk/targetSdk 34`.

### Configurar a chave da API

1. Pegue uma chave gratuita em https://aistudio.google.com/app/apikey
2. Em `local.properties` (não versionado), preencha:
   ```properties
   GOOGLE_API_KEY=sua_chave_aqui
   ```
   A chave é lida em tempo de build e exposta em `BuildConfig.GEMINI_API_KEY`
   (nunca fica escrita no código). Sem ela, o app abre e avisa que a chave falta.
3. O modelo padrão é `gemini-3.8-flash` (veja `GEMINI_MODEL` em `app/build.gradle.kts`).

### Palavra de ativação "Jarvis" (sempre ativo)

O app pode ficar **ouvindo a palavra "Jarvis"** em segundo plano (mesmo fechado /
tela desligada). Ao ouvir "Jarvis", ele capta seu comando, consulta o Gemini e
responde por voz — um ciclo por turnos, conduzido por um **serviço em primeiro
plano** (notificação fixa). Como ele **nunca escuta enquanto fala**, funciona no
**alto-falante, sem fone**.

A detecção usa o **Vosk** — reconhecimento de voz **100% no aparelho**, com um
modelo pt-BR **embutido no app** (nada é baixado em tempo de uso). **Não precisa de
chave, cadastro nem internet.** Basta tocar no **ícone de orelha** 👂 na barra
superior para ligar/desligar a escuta e conceder as permissões de **microfone** e
de **notificações**. Na primeira vez, o modelo é descompactado (~poucos segundos).

Como usar:
- Diga **"Jarvis, <seu pedido>"** numa tacada (ex.: "Jarvis, que horas são?"), ou
- diga só **"Jarvis"**, aguarde o "Sim, Senhor?" e então fale o pedido.

Por que Vosk (em vez do `SpeechRecognizer` do Android ou do Picovoice):
- **Sem o bipe** do sistema a cada ciclo — o Vosk tem pipeline de áudio próprio.
- **Offline** e **sem chave** (o Picovoice exigiria chave; o reconhecedor do Google
  depende de internet e gasta mais bateria).
- O custo é o tamanho do app (o modelo pt-BR ocupa ~30 MB no APK).

Observações:
- Enquanto o Jarvis fala ou pensa, o microfone do Vosk fica **pausado** (sem eco),
  por isso funciona no **alto-falante, sem fone**.
- As conversas iniciadas por voz em segundo plano aparecem na **notificação**, não
  na lista de chat (que é da sessão aberta).
- A escuta contínua ainda consome bateria (processa áudio localmente); desligue
  pelo mesmo ícone (ou pela ação "Desligar escuta" na notificação) quando não
  quiser.

### Idioma das respostas (PT ⇄ EN)

Toque no ícone de **tradução** (文A) na barra superior para alternar o idioma em
que o Jarvis **responde**:

- **PT** (padrão): responde em português com voz masculina pt-BR (`ptd`).
- **EN**: responde em **inglês britânico** com voz masculina en-GB (`gbd`/`rjs`),
  no tom do mordomo dos filmes.

A **entrada por voz continua em pt-BR**: no modo EN, o Senhor fala em português e
o Jarvis entende normalmente, mas responde (texto + voz) em inglês. A escolha da
voz é automática (vozes masculinas do Google TTS; femininas como `afs`/`gba` são
evitadas) e registrada no Logcat sob a tag `JarvisTTS` para conferência.

> A escuta em segundo plano ("Jarvis" sempre ativo) responde em português.

### Compilar e instalar

```powershell
# Gerar o APK de debug
.\gradlew :app:assembleDebug
# APK em: app\build\outputs\apk\debug\app-debug.apk

# Ou instalar direto num aparelho/emulador conectado:
.\gradlew :app:installDebug
```

Na primeira execução o app pede a **permissão de microfone**; conceda para usar a
voz. Toque no microfone para o modo mãos-livres, no alto-falante para silenciar a
voz, e na lixeira para limpar a conversa.

> **Certificado SSL (proxy/antivírus).** Se o Gradle falhar ao baixar dependências
> com erro de SSL (`PKIX path validation failed` / handshake derrubado), é o mesmo
> problema que o `truststore` resolve na versão Python: o JVM não confia na
> autoridade injetada pelo antivírus/proxy. No Android Studio isso não costuma
> ocorrer (ele usa a loja do sistema); pela linha de comando com o JDK 17, aponte o
> Gradle para a loja do Windows com
> `-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT`.

---

## 💻 Versão desktop (Python)

### Instalação

```powershell
pip install -r requirements.txt
```

## Configuração

1. Pegue uma chave gratuita no Google AI Studio: https://aistudio.google.com/app/apikey
2. Copie `.env.example` para `.env` e cole sua chave em `GOOGLE_API_KEY`.

## Uso

```powershell
python jarvis.py
```

Comandos durante a conversa:

| Comando                | Ação                                        |
|------------------------|---------------------------------------------|
| `sair` / `encerrar`    | Encerra                                     |
| `/microfone` (`/mic`)  | Liga/desliga a entrada por voz (mãos-livres)|
| `/mudo`                | Liga/desliga a voz de resposta              |
| `/limpar`              | Apaga o histórico da conversa               |
| `/voz`                 | Lista as vozes disponíveis                  |
| `/ajuda`               | Mostra os comandos                          |

## Entrada por voz (mãos-livres)

Digite `/microfone` (ou diga "ativar microfone") para conversar **falando**.
O Jarvis grava pelo microfone, **para sozinho quando você silencia**, transcreve
a sua fala com o próprio Gemini e responde por voz — um ciclo contínuo de conversa.

- Para voltar ao teclado: diga **"modo texto"** ou digite `/microfone`.
- Para encerrar: diga **"encerrar"** / **"sair"** ou tecle `Ctrl+C`.
- Se o microfone disparar com ruído ou demorar a parar, ajuste
  `JARVIS_MIC_LIMIAR` e `JARVIS_MIC_SILENCIO` no `.env`.

A captura usa `sounddevice` (sem compilar nada) e a transcrição reaproveita a sua
chave do Gemini — não é preciso outro serviço de voz.

## Sobre a voz

Por padrão o Jarvis usa a **voz neural masculina `pt-BR-AntonioNeural`** via
`edge-tts` (gratuita, natural, "cinematográfica"), com tom levemente mais grave e
pausado. O MP3 é reproduzido pelo MCI nativo do Windows — sem players externos.
Requer **conexão com a internet**.

- `/voz` lista as vozes masculinas/femininas de PT-BR disponíveis no edge-tts.
- Trocar de voz: edite `JARVIS_EDGE_VOICE` no `.env`.
- Ajustar gravidade/ritmo: `JARVIS_EDGE_PITCH` e `JARVIS_EDGE_RATE`.

### Voz offline (sem internet)
Defina `JARVIS_TTS=sapi` no `.env` para usar as vozes nativas do Windows via
`pyttsx3`. Neste PC há **Maria (PT-BR, feminina)** e **Zira (EN)**. Para uma voz
masculina offline, instale uma em Configurações → Hora e Idioma → Fala → Adicionar
vozes (ex.: "Microsoft Daniel") e aponte com `JARVIS_VOICE_ID=Daniel`.

> Se a voz neural falhar por falta de internet, o Jarvis cai automaticamente para
> a voz do Windows.

### Certificados (SSL)
O `truststore` é usado para o Python confiar na loja de certificados do Windows —
isso evita erros de SSL do `edge-tts` em máquinas atrás de proxy ou antivírus.

## Ajustes finos (no `.env`)

- `GEMINI_MODEL` — ex.: `gemini-3.8-flash` (rápido), `gemini-3.6-flash` (estável)
- `JARVIS_RATE` — velocidade da fala (padrão `165`; menor = mais pausado/solene)
- `JARVIS_VOZ=0` — inicia com a voz desligada
