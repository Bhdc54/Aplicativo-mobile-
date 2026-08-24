# PeritaVision — Integração com o Mentra Live (seu próprio app, sem o app da Mentra)

Este app conecta **direto** no Mentra Live por Bluetooth usando o SDK oficial
`com.mentraglass:bluetooth-sdk`. Não usa o app consumidor da Mentra nem a nuvem
da Mentra — o *companion* é este app, e ele funciona offline.

## O que foi adicionado

- `device/MentraGlassesDevice.kt` — implementa `GlassesDevice` usando o SDK BLE:
  scan/connect ao Mentra Live, `requestPhoto` (foto sobe por Wi-Fi ao webhook do
  backend), microfone (`setMicState` + PCM 16 kHz via `onMicPcm` → callback `onPcm`),
  e botão da montura (`onButtonPress` → foto).
- `device/GlassesDeviceFactory.kt` — ponto único de injeção; `Tipo.MENTRA` é o
  padrão. `PHONE` continua como muleta de teste; `ROKID` é do estudo anterior.
- `GlassesEvent.CapturaRemota` — novo evento: no Mentra a foto não vira arquivo
  local; ela sobe ao backend, que sela a custódia (SHA-256 + trilha) no servidor.
- `MainActivity` — cria o device pelo factory, conecta ao Mentra no `LaunchedEffect`,
  trata `CapturaRemota` e pede as permissões BLE.
- Build: `settings.gradle.kts` (repo JitPack), `gradle.properties`
  (`mentraSdkVersion=0.1.21-beta.5`), `app/build.gradle.kts` (Java 17, dependência
  do SDK, `packaging` de libs nativas), `AndroidManifest.xml` (BLUETOOTH_SCAN/CONNECT,
  POST_NOTIFICATIONS, feature `bluetooth_le`).

## Como conectar/rodar (no Android Studio)

1. O app roda **no celular** (não nos óculos). Ligue o celular por USB.
2. Ligue o Mentra Live e deixe o Bluetooth do celular ativo.
3. Rode o app; conceda as permissões (câmera, microfone, localização, Bluetooth).
4. O app escaneia e conecta ao Mentra Live automaticamente (MVP: primeiro óculos
   encontrado). O botão da montura dispara a foto; o mic alimenta a narração.

Para desenvolver **sem óculos**, troque em `MainActivity`:
`TIPO_DISPOSITIVO = GlassesDeviceFactory.Tipo.PHONE`.

## Decisões que precisam da sua confirmação

1. **Modelo de custódia da foto.** O Mentra sobe o JPEG por Wi-Fi direto ao
   webhook do backend (`/webhooks/captura/<requestId>`), e a custódia é selada
   **no servidor**. Isso difere do `PhoneGlassesDevice`, que sela no telefone.
   O GPS do momento da captura fica no celular — se ele precisa entrar na custódia,
   o app tem que enviá-lo junto (hoje o backend aceita GPS opcional no webhook).
   Alternativa: usar o modo "foto direto no telefone" do SDK e selar localmente.
2. **Vídeo fora do MVP.** Conforme a arquitetura (foto + áudio, sem vídeo por
   bateria), `iniciarVideo()` apenas emite um aviso. Habilitar depois via
   `setGalleryModeEnabled`/`setButtonVideoRecordingSettings`.
3. **Conexão automática.** O MVP conecta no primeiro Mentra encontrado. Em sala
   com vários óculos, a doc do SDK recomenda um seletor explícito — trocar antes
   de produção.

## Símbolos do SDK a verificar no Android Studio

O código segue a API documentada do starter kit (SDK 0.1.x-beta). Confirme com o
autocomplete, pois nomes de campo podem variar por versão:

- `MicPcmEvent.pcm` (ByteArray) e `MicPcmEvent.sampleRate` — usados em `onMicPcm`.
- `PhotoResponseEvent` — hoje só sinalizamos o `requestId` que nós geramos; se
  quiser ler `uploadUrl`/sucesso do evento, ligue os campos reais.
- `MentraBluetoothSdkListener` como interface (a doc mostra `interface`; se a sua
  versão expõe um `MentraBluetoothSdkCallback` base, ajuste a herança).

## Próximo passo natural

Ligar ao backend: chamar `POST /v1/sessoes/{id}/capturas/solicitar` para obter
`requestId` + `webhookUrl` + `authToken` de uso único e passá-los ao
`MentraGlassesDevice.MentraConfig`, fechando o fluxo foto → webhook → laudo.

## Riscos registrados

- O SDK está em **beta** (`0.1.21-beta.5`). Para prova pericial, tratar como risco
  de maturidade e fixar a versão.
- O primeiro build pode baixar dependências nativas (GStreamer) se recursos de
  streaming forem usados; foto + áudio + custódia (o MVP) não exigem streaming.
