# PeritaVision — Guia do App (Android)

App que roda no **celular** e conecta no óculos **Mentra Live** por Bluetooth,
pelo seu próprio app (sem o app da Mentra). Captura foto/áudio e sela a
**cadeia de custódia** (hash + GPS + log) de cada evidência.

## Onde fica cada coisa

Tudo em `app/src/main/java/com/example/peritavision/`:

```
MainActivity.kt      A TELA. Monta a interface (Compose), pede permissões,
                     e liga os botões: conectar óculos, tirar foto, áudio,
                     comando de voz. É por aqui que você começa a ler.

device/              O "óculos" por trás de uma interface — a UI nunca fala
                     com o hardware direto.
  GlassesDevice.kt         A INTERFACE + os eventos (foto capturada, conexão…).
  GlassesDeviceFactory.kt  Escolhe qual óculos usar (MENTRA ou PHONE de teste).
  MentraGlassesDevice.kt   O óculos REAL: BLE, scan/connect, foto (requestPhoto),
                           microfone (mic_pcm), botão da haste. ← o principal.
  PhoneGlassesDevice.kt    Muleta de teste: usa a câmera do celular no lugar
                           do óculos, pra desenvolver sem o hardware.

voice/
  VoiceTrigger.kt    COMANDO DE VOZ. Escuta o microfone do celular e, ao ouvir
                     "capturar", dispara a foto. Reconhecimento do próprio
                     Android (offline, sem backend).

capture/             Detalhes de câmera/áudio do celular (usados pelo Phone).
  PhotoCaptureManager.kt · VideoCaptureManager.kt · AudioCaptureManager.kt

domain/              REGRAS DE NEGÓCIO (não dependem do hardware).
  Model.kt           Tipos: Evidencia, TipoEvidencia, SinteseCaso…
  Hashing.kt         SHA-256.
  GeoProvider.kt     GPS do celular.
  CofreCustodia.kt   Cofre: pasta das evidências + log append-only encadeado
                     por hash (adulterar uma linha quebra a verificação).
  SelarCustodia.kt   "Sela" uma evidência: calcula hash + GPS + carimba no log.

data/
  Mocks.kt           Dados falsos de caso/código de barras (o "LER CÓDIGO").

ui/                  Cores e tema.
```

## Como o fluxo funciona (linha por linha, alto nível)

1. `MainActivity` cria o `device` pelo `GlassesDeviceFactory` (Mentra por padrão).
2. Botão **CONECTAR ÓCULOS** → `MentraGlassesDevice.conectar()` → scan BLE → conecta.
3. **TIRAR FOTO** (ou botão da haste, ou voz) → `device.capturarFoto()`.
4. No Mentra, a foto sobe por Wi-Fi para o **backend** (webhook). No modo PHONE,
   a foto é salva no celular e o `SelarCustodia` sela a custódia na hora.
5. Cada evento do óculos volta pela `device.eventos` e a tela reage (status,
   "óculos conectado", "evidência selada"…).

## Comando de voz — onde está e como testar

Está em `voice/VoiceTrigger.kt`, ligado na `MainActivity` no botão
**ATIVAR COMANDO DE VOZ**. Ao ligar, fale **"capturar"** → dispara a foto.

> Dica para ver funcionando AGORA, sem óculos e sem backend: em `MainActivity.kt`,
> troque `TIPO_DISPOSITIVO` para `GlassesDeviceFactory.Tipo.PHONE`. Aí a foto usa
> a câmera do celular e sela a custódia localmente — você fala "capturar" e vê a
> evidência selada aparecer na tela.

No caminho oficial (Mentra + backend), o comando de voz "de verdade" virá do
microfone dos **óculos** → backend (ASR) → comando. Este `VoiceTrigger` é a
versão local e simples; os dois podem coexistir.

## Trocar de hardware / modo

Uma linha em `MainActivity.kt`:

```kotlin
private val TIPO_DISPOSITIVO = GlassesDeviceFactory.Tipo.MENTRA  // ou .PHONE
```

## O que ainda depende do backend

A foto no Mentra precisa do **backend** rodando (é pra onde o óculos sobe o JPEG
por Wi-Fi). Enquanto o backend não estiver configurado no app, tocar em TIRAR
FOTO (ou falar "capturar") no modo MENTRA mostra o aviso "webhook não
configurado" — é esperado. No modo PHONE, funciona sem backend.
```

