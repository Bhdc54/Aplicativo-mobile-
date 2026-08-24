package com.example.peritavision.device

import android.content.Context
import android.util.Log
import com.example.peritavision.domain.TipoEvidencia
import com.mentra.bluetoothsdk.ButtonPressEvent
import com.mentra.bluetoothsdk.DeviceModel
import com.mentra.bluetoothsdk.GlassesRuntimeState
import com.mentra.bluetoothsdk.MentraBluetoothSdk
import com.mentra.bluetoothsdk.MentraBluetoothSdkConfig
import com.mentra.bluetoothsdk.MentraBluetoothSdkListener
import com.mentra.bluetoothsdk.MicPcmEvent
import com.mentra.bluetoothsdk.PhotoCompression
import com.mentra.bluetoothsdk.PhotoRequest
import com.mentra.bluetoothsdk.PhotoSize
import com.mentra.bluetoothsdk.DeviceManager
import com.mentra.bluetoothsdk.ScanStopReason
import com.mentra.bluetoothsdk.sgcs.MentraLive
import com.mentra.bluetoothsdk.StreamRequest
import com.mentra.bluetoothsdk.StreamState
import com.mentra.bluetoothsdk.StreamStatus
import com.mentra.bluetoothsdk.StreamStatusEvent
import com.mentra.bluetoothsdk.StreamVideoConfig
import com.mentra.bluetoothsdk.WifiStatus
import com.mentra.bluetoothsdk.SwipeEvent
import com.mentra.bluetoothsdk.TouchEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

/**
 * Implementacao de [GlassesDevice] para o MENTRA LIVE, conectando DIRETO pelo
 * SEU app via Bluetooth com o SDK oficial `com.mentraglass:bluetooth-sdk`.
 */
class MentraGlassesDevice(
    private val context: Context,
    private val destino: File,
    private val config: MentraConfig = MentraConfig(),
) : GlassesDevice, MentraBluetoothSdkListener {

    data class MentraConfig(
        val appId: String = "br.gov.mt.politec.peritavision",
        /** Base do webhook do backend; a foto vai para "$webhookBaseUrl/<requestId>". */
        val webhookBaseUrl: String? = null,
        /** Token de uso unico emitido pelo backend (/capturas/solicitar). */
        val authToken: String? = null,
        /** Conecta ao dispositivo padrao salvo; senao, escaneia. */
        val autoConectar: Boolean = true,
    )

    private val _eventos = MutableSharedFlow<GlassesEvent>(extraBufferCapacity = 32)
    override val eventos: SharedFlow<GlassesEvent> = _eventos.asSharedFlow()

    /** PCM da narracao (16 kHz) — alta frequencia, vai por callback, nao por evento. */
    var onPcm: ((pcm: ByteArray, sampleRate: Int) -> Unit)? = null

    /**
     * Como obter a autorizacao de UMA captura no backend.
     * O backend emite um token de USO UNICO por foto (POST
     */
    var obterAutorizacao: (suspend () -> AutorizacaoCaptura?)? = null

    /** requestId + URL + token emitidos pelo backend para uma unica foto. */
    data class AutorizacaoCaptura(
        val requestId: String,
        val webhookUrl: String,
        val authToken: String,
    )

    /** Estado de conexao observavel pela UI (true quando os oculos estao prontos). */
    var conectado: Boolean = false
        private set

    private val sdk: MentraBluetoothSdk = MentraBluetoothSdk.create(
        context = context.applicationContext,
        config = MentraBluetoothSdkConfig(),
        listener = this,
    )

    private var micLigado = false
    private var pararScan: (() -> Unit)? = null
    private var tentandoConectar = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Reconexao automatica: se o primeiro scan nao achar nada (ou o pareamento
    // BLE for negado logo de cara), tenta de novo sozinho por algumas vezes
    private var tentativasAutomaticas = 0
    private val MAX_TENTATIVAS_AUTOMATICAS = 3

    // ------------------------------------------------------------------------
    // Conexao (nao esta na interface; especifico do acessorio BLE)
    // ------------------------------------------------------------------------

    /** Inicia a conexao: tenta o dispositivo padrao; se nao houver, escaneia. */
    fun conectar() {
        tentativasAutomaticas = 0
        if (config.autoConectar && sdk.getDefaultDevice() != null) {
            sdk.connectDefault()
            return
        }
        escanear()
    }

    /**
     * Escaneia por Mentra Live. MVP (1 par por bancada): conecta no primeiro
     * encontrado. PRODUCAO (varios oculos na sala): trocar por um seletor
     */
    fun escanear() {
        tentandoConectar = false
        // sdk.scan() LANÇA BluetoothSdkException com o Bluetooth desligado.
        // Este método também é chamado pelo retry automático (onScanStopped),
        // fora do try/catch da tela — sem esta guarda, desligar o Bluetooth no
        // meio de um retry derrubava o app. Vira evento de erro, não crash.
        val session = try {
            sdk.scan(DeviceModel.MENTRA_LIVE, timeoutMs = 6_000) { devices ->
                if (tentandoConectar) return@scan      // ja iniciamos uma conexao
                val alvo = devices.firstOrNull() ?: return@scan
                tentandoConectar = true
                sdk.connect(alvo)
                sdk.setDefaultDevice(alvo)
            }
        } catch (e: Exception) {
            _eventos.tryEmit(
                GlassesEvent.Erro(
                    "Ligue o Bluetooth do aparelho e toque em conectar de novo."
                )
            )
            return
        }
        // Guardamos o "parar" numa lambda para nao depender do nome do tipo de sessao.
        pararScan = { session.stop() }
    }

    /**
     * Scan terminou. Se nao achou/conectou nada, tenta de novo automaticamente
     * (até [MAX_TENTATIVAS_AUTOMATICAS] vezes) antes de pedir ação manual —
     */
    override fun onScanStopped(reason: ScanStopReason) {
        if (!conectado && !tentandoConectar) {
            if (tentativasAutomaticas < MAX_TENTATIVAS_AUTOMATICAS) {
                tentativasAutomaticas++
                _eventos.tryEmit(GlassesEvent.Aviso("Tentando conectar de novo (${tentativasAutomaticas}/$MAX_TENTATIVAS_AUTOMATICAS)..."))
                escanear()
                return
            }
            _eventos.tryEmit(
                GlassesEvent.Erro(
                    "Nenhum óculos encontrado por BLE. Ligue a Localização (GPS), " +
                        "deixe o óculos bem perto e toque em conectar de novo."
                )
            )
        }
    }

    fun desconectar() {
        sdk.disconnect()
    }

    // ------------------------------------------------------------------------
    // Wi-Fi DOS OCULOS  — pre-requisito da foto, nao um extra

    /**
     * Manda as credenciais da Wi-Fi para os oculos (provisionamento por BLE).
     * `sendWifiCredentials` e suspend e devolve o estado final do Wi-Fi, entao
     */
    fun configurarWifi(ssid: String, senha: String) {
        if (!conectado) {
            _eventos.tryEmit(GlassesEvent.Erro("conecte os óculos antes de configurar o Wi-Fi"))
            return
        }
        if (ssid.isBlank()) {
            _eventos.tryEmit(GlassesEvent.Erro("informe o nome da rede (SSID)"))
            return
        }
        scope.launch {
            try {
                // Diagnóstico primeiro: os óculos ENXERGAM essa rede? (2.4 GHz apenas)
                _eventos.tryEmit(GlassesEvent.Aviso("Procurando \"$ssid\" pelos óculos..."))
                val redes = runCatching { sdk.requestWifiScan() }.getOrDefault(emptyList())
                if (redes.isNotEmpty() && redes.none { it.ssid.equals(ssid.trim(), ignoreCase = true) }) {
                    val visiveis = redes.sortedByDescending { it.signalStrength }
                        .take(5).joinToString(", ") { it.ssid }
                    // Aviso, nao bloqueio: o scan pode estar desatualizado.
                    _eventos.tryEmit(
                        GlassesEvent.Aviso(
                            "Atenção: os óculos não listaram \"$ssid\" (veem: $visiveis). Tentando mesmo assim..."
                        )
                    )
                }
                _eventos.tryEmit(GlassesEvent.Aviso("Enviando Wi-Fi \"$ssid\" aos óculos..."))
                val resposta = sdk.sendWifiCredentials(ssid = ssid.trim(), password = senha)
                Log.d(TAG, "sendWifiCredentials -> $resposta")
                val st = resposta.status
                if (st is WifiStatus.Connected) {
                    wifiConectado = true
                    _eventos.tryEmit(GlassesEvent.Wifi(true, st.ssid))
                } else {
                    wifiConectado = false
                    _eventos.tryEmit(GlassesEvent.Wifi(false, ssid))
                    val motivo = resposta.error?.let { " ($it)" } ?: " — confira a senha"
                    _eventos.tryEmit(GlassesEvent.Erro("óculos não entraram em \"$ssid\"$motivo"))
                }
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                when {
                    msg.contains("timed out", ignoreCase = true) -> _eventos.tryEmit(
                        GlassesEvent.Aviso(
                            "Os óculos ainda estão entrando na rede — aguarde ~20s; o estado atualiza sozinho."
                        )
                    )
                    msg.contains("already waiting", ignoreCase = true) -> _eventos.tryEmit(
                        GlassesEvent.Aviso("Envio de Wi-Fi já em andamento — aguarde, sem tocar de novo.")
                    )
                    else -> _eventos.tryEmit(GlassesEvent.Erro("falha ao enviar Wi-Fi: $msg"))
                }
            }
        }
    }

    /** Pede aos oculos a lista de redes visiveis. Tambem e suspend. */
    fun escanearWifi() {
        scope.launch {
            val redes = runCatching { sdk.requestWifiScan() }.getOrNull()
            Log.d(TAG, "redes visiveis pelos oculos: $redes")
        }
    }

    override fun onWifiStatusChanged(event: com.mentra.bluetoothsdk.WifiStatusEvent) {
        val st = event.status
        val ligado = st is WifiStatus.Connected
        val ssid = (st as? WifiStatus.Connected)?.ssid
        wifiConectado = ligado
        Log.d(TAG, "wifi dos oculos: conectado=$ligado ssid=$ssid | bruto=$event")
        _eventos.tryEmit(GlassesEvent.Wifi(ligado, ssid))
    }

    /** Ultimo estado de Wi-Fi reportado pelos oculos. */
    var wifiConectado: Boolean = false
        private set

    /**
     * Resultado real do upload da foto. `requestPhoto` retornar sem excecao so
     * quer dizer que o comando foi aceito — quem confirma que o JPEG chegou ao
     */
    override fun onPhotoResponse(event: com.mentra.bluetoothsdk.PhotoResponseEvent) {
        val url = campoTexto(event, "uploadUrl", "url")
        val req = campoTexto(event, "requestId", "id") ?: ultimoRequestId.orEmpty()
        val erro = campoTexto(event, "error", "errorMessage", "message")
        Log.d(TAG, "photo_response: req=$req url=$url erro=$erro | bruto=$event")
        if (erro != null) {
            _eventos.tryEmit(GlassesEvent.Erro("óculos não conseguiram enviar a foto: $erro"))
            return
        }
        _eventos.tryEmit(GlassesEvent.CapturaRemota(TipoEvidencia.FOTO, req, uploadUrl = url))
    }

    /** Guardado para casar o photo_response quando o evento nao trouxer o id. */
    private var ultimoRequestId: String? = null

    // --- leitura tolerante de campos do SDK (nomes variam entre betas) -------

    private fun campoTexto(alvo: Any, vararg nomes: String): String? {
        for (n in nomes) {
            val v = valorDe(alvo, n) ?: continue
            val s = v.toString()
            if (s.isNotBlank() && s != "null") return s
        }
        return null
    }

    private fun campoBooleano(alvo: Any, vararg nomes: String): Boolean {
        for (n in nomes) {
            when (val v = valorDe(alvo, n)) {
                is Boolean -> return v
                is String -> if (v.equals("true", true)) return true
            }
        }
        return false
    }

    /** Tenta getNome() e depois o campo `nome`. Devolve null se nao existir. */
    private fun valorDe(alvo: Any, nome: String): Any? {
        val getter = "get" + nome.replaceFirstChar { it.uppercase() }
        runCatching { return alvo.javaClass.getMethod(getter).invoke(alvo) }
        runCatching {
            return alvo.javaClass.getDeclaredField(nome).apply { isAccessible = true }.get(alvo)
        }
        return null
    }

    // ------------------------------------------------------------------------
    // Callbacks do SDK (MentraBluetoothSdkListener)
    // ------------------------------------------------------------------------

    override fun onGlassesChanged(glasses: GlassesRuntimeState) {
        conectado = glasses is GlassesRuntimeState.Connected
        if (!conectado) tentandoConectar = false
        else {
            tentativasAutomaticas = 0 // conectou: zera o contador de retentativas
            // ativarAudioNosOculos() — desligado por ora: suspeita de derrubar
            // a conexao BLE em alguns firmwares. Fala sai pelo celular.
        }
        Log.d(TAG, "glasses: $glasses (conectado=$conectado)")
        _eventos.tryEmit(GlassesEvent.Conexao(conectado))
    }

    /**
     * Liga o servico de fone Bluetooth (HFP) DOS OCULOS, para as confirmacoes
     * faladas sairem no alto-falante deles. O SDK nao expoe isso na API publica;
     * o campo privado deviceManager e alcancado por reflexao (SDK 0.1.21-beta.5).
     * Depois de ligado, pareie "Mentra Live" no Bluetooth do celular UMA vez.
     */
    private fun ativarAudioNosOculos() {
        try {
            val campo = MentraBluetoothSdk::class.java.getDeclaredField("deviceManager")
            campo.isAccessible = true
            val dm = campo.get(sdk) as? DeviceManager ?: return
            val live = dm.sgc as? MentraLive ?: return
            live.enableHfpAudioServer(true)
            _eventos.tryEmit(
                GlassesEvent.Aviso("áudio dos óculos ativado — pareie \"Mentra Live\" no Bluetooth do celular (1ª vez)")
            )
        } catch (e: Exception) {
            Log.w(TAG, "nao consegui ligar o HFP dos oculos: ${e.message}")
        }
    }

    /** Botao/haste da montura → captura foto (fallback tatil da voz). */
    override fun onButtonPress(event: ButtonPressEvent) {
        capturarFoto()
    }

    /** Swipe na barra de toque → descartar ultima (sinalizado como erro/aviso). */
    override fun onSwipe(event: SwipeEvent) {
        _eventos.tryEmit(GlassesEvent.Erro("descartar ultima captura (swipe) — a implementar"))
    }

    override fun onTouch(event: TouchEvent) { /* reservado */ }

    /** Frames PCM do microfone dos oculos → repassa ao app (WS de audio → ASR). */
    override fun onMicPcm(event: MicPcmEvent) {
        framesPcmRecebidos++
        // Diagnostico: o primeiro frame prova que o microfone DOS OCULOS esta
        // mesmo transmitindo. Se chegam frames mas nenhuma transcricao, o
        // problema e o modelo de reconhecimento, nao o microfone.
        if (framesPcmRecebidos == 20) {
            _eventos.tryEmit(GlassesEvent.Aviso("Microfone dos óculos transmitindo ✓"))
        }
        onPcm?.invoke(event.pcm, event.sampleRate)
    }

    override fun onError(error: com.mentra.bluetoothsdk.BluetoothError) {
        _eventos.tryEmit(GlassesEvent.Erro("SDK Mentra: $error"))
    }

    // ------------------------------------------------------------------------
    // Interface GlassesDevice
    // ------------------------------------------------------------------------

    override fun capturarFoto() {
        if (!conectado) {
            _eventos.tryEmit(GlassesEvent.Erro("óculos não conectado — toque em CONECTAR ÓCULOS"))
            return
        }
        // requestPhoto e suspend: sobe o JPEG por Wi-Fi ao webhook e so retorna
        // quando termina. Por isso tudo roda numa corrotina.
        scope.launch {
            // 1) Autorizacao no backend (token de uso unico, um por foto).
            val autorizacao = try {
                obterAutorizacao?.invoke()
            } catch (e: Exception) {
                _eventos.tryEmit(GlassesEvent.Erro("backend recusou a captura: ${e.message}"))
                return@launch
            }
            if (autorizacao == null) {
                _eventos.tryEmit(
                    GlassesEvent.Erro("nenhuma sessão aberta — toque em INICIAR SESSÃO antes de capturar")
                )
                return@launch
            }

            // 2a) Vídeo transmitindo: NAO disputa a câmera (congelava o vídeo).
            // A captura fica marcada; o servidor recorta o quadro exato do
            // vídeo no "finalizar" — foto garantida, vídeo intacto.
            if (streamAtivo) {
                _eventos.tryEmit(
                    GlassesEvent.CapturaRemota(TipoEvidencia.FOTO, autorizacao.requestId, null)
                )
                _eventos.tryEmit(GlassesEvent.Aviso("foto marcada no vídeo da sessão"))
                return@launch
            }

            // 2b) Sem vídeo: os oculos sobem o JPEG direto para o webhook.
            ultimoRequestId = autorizacao.requestId
            try {
                sdk.requestPhoto(
                    PhotoRequest(
                        requestId = autorizacao.requestId,
                        size = PhotoSize.MEDIUM,
                        webhookUrl = autorizacao.webhookUrl,
                        authToken = autorizacao.authToken,
                        compress = PhotoCompression.MEDIUM,
                        sound = true,
                        exposureTimeNs = null,
                        iso = null,
                    )
                )
                // NAO emitimos CapturaRemota aqui: requestPhoto voltar sem erro
                // significa apenas "comando aceito". Quem confirma que o JPEG
                // chegou ao webhook e o callback onPhotoResponse.
                if (!wifiConectado) {
                    _eventos.tryEmit(
                        GlassesEvent.Erro(
                            "Foto disparada, mas os óculos estão SEM Wi-Fi — " +
                                "a imagem não vai chegar ao servidor. Configure o Wi-Fi dos óculos."
                        )
                    )
                }
            } catch (e: Exception) {
                _eventos.tryEmit(GlassesEvent.Erro("falha ao capturar foto: ${e.message}"))
            }
        }
    }

    // ------------------------------------------------------------------------
    // VIDEO DA SESSAO — os oculos transmitem RTMP direto ao servidor (startStream).

    private var streamAtivo = false

    override fun iniciarVideo(urlStream: String?) {
        if (urlStream.isNullOrBlank()) {
            _eventos.tryEmit(GlassesEvent.Erro("sessão sem URL de vídeo (RTMP desligado no servidor)"))
            return
        }
        if (!conectado) {
            _eventos.tryEmit(GlassesEvent.Erro("óculos não conectados — vídeo não iniciado"))
            return
        }
        if (!wifiConectado) {
            _eventos.tryEmit(GlassesEvent.Aviso("óculos sem Wi-Fi: o vídeo só sobe quando o Wi-Fi conectar"))
        }
        scope.launch {
            try {
                _eventos.tryEmit(GlassesEvent.Aviso("iniciando vídeo dos óculos..."))
                sdk.startStream(
                    StreamRequest(
                        streamUrl = urlStream,
                        streamId = "pv-${System.currentTimeMillis()}",
                        // audio da narracao ja vai pelo BLE; sound=false evita suspender o mic
                        sound = false,
                        video = StreamVideoConfig(width = 1280, height = 720, bitrate = 2_000_000, fps = 30),
                    )
                )
                streamAtivo = true
                _eventos.tryEmit(GlassesEvent.GravacaoIniciada(TipoEvidencia.VIDEO))
            } catch (e: Exception) {
                _eventos.tryEmit(GlassesEvent.Erro("vídeo dos óculos: ${e.message}"))
            }
        }
    }

    override fun pararVideo() {
        if (!streamAtivo) return
        streamAtivo = false
        scope.launch {
            // Os óculos às vezes perdem o primeiro stop — insiste até 3 vezes.
            var parou = false
            for (tentativa in 1..3) {
                try {
                    sdk.stopStream()
                    parou = true
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "stopStream tentativa $tentativa falhou: ${e.message}")
                    kotlinx.coroutines.delay(2000)
                }
            }
            if (parou) _eventos.tryEmit(GlassesEvent.Aviso("vídeo da sessão encerrado"))
            else _eventos.tryEmit(
                GlassesEvent.Erro("os óculos não confirmaram o fim do vídeo — se o LED seguir aceso, desligue e ligue os óculos")
            )
        }
    }

    override fun onStreamStatus(event: StreamStatusEvent) {
        when (event.status.state) {
            StreamState.STREAMING ->
                _eventos.tryEmit(GlassesEvent.Aviso("vídeo dos óculos TRANSMITINDO para o servidor"))
            StreamState.RECONNECTING ->
                _eventos.tryEmit(GlassesEvent.Aviso("vídeo: reconectando..."))
            StreamState.RECONNECT_FAILED ->
                _eventos.tryEmit(GlassesEvent.Erro("vídeo: reconexão falhou — confira o Wi-Fi dos óculos"))
            StreamState.ERROR -> {
                val detalhe = (event.status as? StreamStatus.Error)?.errorDetails ?: "erro no stream"
                _eventos.tryEmit(GlassesEvent.Erro("vídeo dos óculos: $detalhe"))
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------------
    // COMANDO DE VOZ pelos MICROFONES DOS OCULOS (transcricao local)

    /** Chamado quando os oculos ouvem uma palavra-chave de captura. */
    var onComandoVoz: (() -> Unit)? = null

    /**
     * Palavras que disparam a captura.
     * Inclui INGLES de proposito: a transcricao local dos oculos costuma vir
     */
    var palavrasDeComando: List<String> = listOf(
        // portugues
        "captur", "foto", "registrar",
        // ingles (modelo de transcricao dos oculos)
        "capture", "photo", "picture", "shoot", "snap",
    )

    private var escutandoComando = false
    private var ultimoComandoMs = 0L

    // Diagnostico: separa "o microfone nao liga" de "liga mas nao transcreve".
    private var transcricoesRecebidas = 0
    private var framesPcmRecebidos = 0

    /** Liga o microfone dos oculos pedindo os eventos de transcricao. */
    fun iniciarComandoDeVoz() {
        if (!conectado) {
            _eventos.tryEmit(GlassesEvent.Erro("conecte os óculos para usar o comando de voz"))
            return
        }
        if (escutandoComando) return
        transcricoesRecebidas = 0
        framesPcmRecebidos = 0
        try {
            sdk.setMicState(enabled = true, sendTranscript = true)
            escutandoComando = true
            micLigado = true
            _eventos.tryEmit(GlassesEvent.Aviso("Óculos ouvindo — diga \"capturar\""))
        } catch (e: Exception) {
            _eventos.tryEmit(GlassesEvent.Erro("falha ao ligar escuta dos óculos: ${e.message}"))
            return
        }

        // Veredito automatico: 8s depois, diz o que esta acontecendo de fato.
        scope.launch {
            kotlinx.coroutines.delay(8_000)
            if (!escutandoComando) return@launch
            if (transcricoesRecebidas > 0) return@launch          // esta transcrevendo

            if (framesPcmRecebidos > 0) {
                // Mic OK, transcricao embarcada nao. Isso e ESPERADO neste
                // firmware — e nao e problema: o audio ja esta seguindo por
                Log.d(TAG, "transcricao embarcada sem resposta; ASR do backend assume")
            } else {
                _eventos.tryEmit(
                    GlassesEvent.Erro(
                        "Os óculos não estão enviando áudio. Use o botão da haste para capturar."
                    )
                )
            }
        }
    }

    fun pararComandoDeVoz() {
        if (!escutandoComando) return
        escutandoComando = false
        micLigado = false
        runCatching { sdk.setMicState(enabled = false) }
    }

    /** Texto reconhecido pelos oculos. Se contiver a palavra-chave, dispara. */
    override fun onLocalTranscription(event: com.mentra.bluetoothsdk.LocalTranscriptionEvent) {
        transcricoesRecebidas++
        val texto = campoTexto(event, "text", "transcript", "transcription")
        Log.d(TAG, "transcricao #$transcricoesRecebidas: texto=$texto | bruto=$event")
        if (texto.isNullOrBlank()) {
            // Chegou evento mas sem texto legivel: mostra o cru para diagnostico.
            _eventos.tryEmit(GlassesEvent.Aviso("Óculos ouviram algo (sem texto): $event"))
            return
        }
        // Mostra SEMPRE o que os oculos entenderam — assim da para ver se ele
        // ouve mas erra a palavra, ou se nao ouve nada.
        _eventos.tryEmit(GlassesEvent.Aviso("Ouvi: \"$texto\""))
        val normalizado = semAcento(texto)
        val bateu = palavrasDeComando.any { normalizado.contains(semAcento(it)) }
        if (!bateu) return
        if (System.currentTimeMillis() - ultimoComandoMs < 2500) return  // debounce
        ultimoComandoMs = System.currentTimeMillis()
        _eventos.tryEmit(GlassesEvent.Aviso("Comando ouvido: \"$texto\" — capturando..."))
        onComandoVoz?.invoke()
    }

    private fun semAcento(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    override fun iniciarAudio() {
        if (micLigado) return
        try {
            sdk.setMicState(enabled = true)
            micLigado = true
            _eventos.tryEmit(GlassesEvent.GravacaoIniciada(TipoEvidencia.AUDIO))
        } catch (e: Exception) {
            _eventos.tryEmit(GlassesEvent.Erro("falha ao ligar microfone dos oculos: ${e.message}"))
        }
    }

    override fun pararAudio() {
        if (!micLigado) return
        // Se a escuta de COMANDO esta ativa, nao desligue o microfone: os dois
        // usam o mesmo mic dos oculos e desligar aqui mataria o comando de voz.
        if (escutandoComando) {
            _eventos.tryEmit(GlassesEvent.Aviso("Narração parada (óculos seguem ouvindo comandos)"))
            return
        }
        try {
            sdk.setMicState(enabled = false)
        } finally {
            micLigado = false
        }
    }

    override fun encerrar() {
        pararScan?.invoke(); pararScan = null
        scope.cancel()
        if (micLigado) { runCatching { sdk.setMicState(enabled = false) }; micLigado = false }
        runCatching { sdk.disconnect() }
        runCatching { sdk.close() }
    }

    companion object {
        private const val TAG = "MentraGlassesDevice"
    }
}
