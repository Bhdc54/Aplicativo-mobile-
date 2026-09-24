package br.com.facilmova.peritavision.device

import android.content.Context
import android.util.Log
import br.com.facilmova.peritavision.domain.TipoEvidencia
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

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

    /** Como obter a autorizacao de UMA captura no backend. */
    var obterAutorizacao: (suspend () -> AutorizacaoCaptura?)? = null

    /** requestId + URL + token emitidos pelo backend para uma unica foto. */
    data class AutorizacaoCaptura(
        val requestId: String,
        val webhookUrl: String,
        val authToken: String,
        /** true = o webhook acima é o receptor local do tablet, e não o endereço público do backend. */
        val peloTablet: Boolean = false,
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

    private var reconexao: Job? = null
    private var desligadoPeloPerito = false
    private var conectadoDesdeMs = 0L
    private val ESPERAS_RECONEXAO_MS = longArrayOf(2_000, 4_000, 8_000, 15_000, 20_000, 30_000, 30_000)

    // Conexao (nao esta na interface; especifico do acessorio BLE)

    /** Inicia a conexao: tenta o dispositivo padrao; se nao houver, escaneia. */
    fun conectar() {
        tentativasAutomaticas = 0
        desligadoPeloPerito = false
        reconexao?.cancel(); reconexao = null
        if (config.autoConectar && sdk.getDefaultDevice() != null) {
            sdk.connectDefault()
            return
        }
        escanear()
    }

    private fun agendarReconexao() {
        if (reconexao?.isActive == true) return
        reconexao = scope.launch {
            for ((i, espera) in ESPERAS_RECONEXAO_MS.withIndex()) {
                delay(espera)
                if (conectado || desligadoPeloPerito) return@launch
                val n = i + 1
                _eventos.tryEmit(
                    GlassesEvent.Aviso("Óculos caíram — reconectando ($n/${ESPERAS_RECONEXAO_MS.size})...")
                )
                Log.w(TAG, "reconexao BLE: tentativa $n")
                try {
                    pararScan?.invoke(); pararScan = null
                    if (sdk.getDefaultDevice() != null) sdk.connectDefault() else escanear()
                } catch (e: Exception) {
                    // Bluetooth do tablet desligado, SDK ocupado com a tentativa anterior... vira log; a próxima volta do laço tenta de novo.
                    Log.w(TAG, "reconexao BLE: tentativa $n nao iniciou: ${e.message}")
                }
            }
            delay(10_000) // dá tempo à última tentativa
            if (!conectado && !desligadoPeloPerito) {
                _eventos.tryEmit(
                    GlassesEvent.Erro(
                        "Os óculos não voltaram sozinhos. Confira se estão ligados e perto " +
                            "do tablet e toque em CONECTAR ÓCULOS."
                    )
                )
            }
        }
    }

    /** Escaneia por Mentra Live. */
    fun escanear() {
        tentandoConectar = false
        // sdk.scan() LANÇA BluetoothSdkException com o Bluetooth desligado.
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

    /** Scan terminou. */
    override fun onScanStopped(reason: ScanStopReason) {
        if (reconexao?.isActive == true) return
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

    /** Desligamento pedido pelo perito: aqui NÃO se reconecta sozinho. */
    fun desconectar() {
        desligadoPeloPerito = true
        reconexao?.cancel(); reconexao = null
        sdk.disconnect()
    }

    // Wi-Fi DOS OCULOS  — pre-requisito da foto, nao um extra

    /** Manda as credenciais da Wi-Fi para os oculos (provisionamento por BLE). */
    fun configurarWifi(ssid: String, senha: String) {
        if (!conectado) {
            _eventos.tryEmit(GlassesEvent.Erro("conecte os óculos antes de configurar o Wi-Fi"))
            return
        }
        if (ssid.isBlank()) {
            _eventos.tryEmit(GlassesEvent.Erro("informe o nome da rede (SSID)"))
            return
        }
        if (enviandoWifi) {
            _eventos.tryEmit(GlassesEvent.Aviso("Envio de Wi-Fi já em andamento — aguarde."))
            return
        }
        enviandoWifi = true
        scope.launch {
            try {
                var esperouPronto = 0
                while (!oculosProntos() && conectado && esperouPronto < 40_000) {
                    if (esperouPronto == 0) {
                        _eventos.tryEmit(GlassesEvent.Aviso("Óculos ainda ligando — a rede vai assim que eles estiverem prontos..."))
                    }
                    delay(1_000); esperouPronto += 1_000
                }
                if (!conectado) return@launch
                // Já estão nesta rede?
                wifiAtualDosOculos()?.let { atual ->
                    if (atual.ssid.equals(ssid.trim(), ignoreCase = true)) {
                        wifiConectado = true
                        _eventos.tryEmit(GlassesEvent.Wifi(true, atual.ssid))
                        return@launch
                    }
                }
                // Diagnóstico primeiro: os óculos ENXERGAM essa rede?
                _eventos.tryEmit(GlassesEvent.Aviso("Procurando \"$ssid\" pelos óculos..."))
                val redes = withTimeoutOrNull(12_000) {
                    runCatching { sdk.requestWifiScan() }.getOrDefault(emptyList())
                } ?: run {
                    Log.w(TAG, "requestWifiScan sem resposta em 12 s — enviando a rede sem o diagnóstico")
                    emptyList()
                }
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
                // Prazo externo: se o SDK não voltar, o estado real chega por
                // onWifiStatusChanged; aqui não pode ficar preso para sempre.
                val resposta = withTimeout(45_000) {
                    sdk.sendWifiCredentials(ssid = ssid.trim(), password = senha)
                }
                Log.d(TAG, "sendWifiCredentials -> ${resposta.status?.javaClass?.simpleName} erro=${resposta.error}")
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
                    // O SDK espera 15 s pela resposta; entrar numa rede (associação + DHCP) leva mais que isso com frequência.
                    msg.contains("timed out", ignoreCase = true) -> {
                        _eventos.tryEmit(GlassesEvent.Aviso("Óculos ainda entrando em \"$ssid\" — conferindo..."))
                        aguardarWifiDepoisDoEnvio(ssid.trim())
                    }
                    msg.contains("already waiting", ignoreCase = true) -> _eventos.tryEmit(
                        GlassesEvent.Aviso("Envio de Wi-Fi já em andamento — aguarde, sem tocar de novo.")
                    )
                    else -> _eventos.tryEmit(GlassesEvent.Erro("falha ao enviar Wi-Fi: $msg"))
                }
            } finally {
                enviandoWifi = false
            }
        }
    }

    /** true enquanto um sendWifiCredentials está em curso. */
    @Volatile private var enviandoWifi = false

    /** O Android dos óculos terminou de ligar? (SDK: `ready` = glasses_ready.) */
    private fun oculosProntos(): Boolean =
        (runCatching { sdk.getGlasses() }.getOrNull() as? GlassesRuntimeState.Connected)?.ready == true

    /** A Wi-Fi que os óculos dizem ter AGORA, pelo estado do SDK — sem esperar evento. */
    private fun wifiAtualDosOculos(): WifiStatus.Connected? =
        (runCatching { sdk.getGlasses() }.getOrNull() as? GlassesRuntimeState.Connected)?.wifi as? WifiStatus.Connected

    private suspend fun aguardarWifiDepoisDoEnvio(ssid: String) {
        var esperou = 0
        while (esperou < 45_000 && conectado) {
            delay(3_000); esperou += 3_000
            wifiAtualDosOculos()?.let { atual ->
                wifiConectado = true
                _eventos.tryEmit(GlassesEvent.Wifi(true, atual.ssid))
                if (!atual.ssid.equals(ssid, ignoreCase = true)) {
                    _eventos.tryEmit(GlassesEvent.Aviso("Os óculos entraram em \"${atual.ssid}\", não em \"$ssid\"."))
                }
                return
            }
            if (esperou == 15_000) pedirStatusDeWifi()
        }
        if (!conectado) return
        wifiConectado = false
        _eventos.tryEmit(GlassesEvent.Wifi(false, ssid))
        _eventos.tryEmit(
            GlassesEvent.Erro(
                "Os óculos não entraram em \"$ssid\". Confira a senha, se a rede é 2,4 GHz " +
                    "(não a versão 5G) e se os óculos estão perto do roteador. Depois toque em Enviar de novo."
            )
        )
    }

    /** Pede aos óculos um wifi_status agora (o SDK só faz isso sozinho no boot). */
    private fun pedirStatusDeWifi() {
        try {
            val campo = MentraBluetoothSdk::class.java.getDeclaredField("deviceManager")
            campo.isAccessible = true
            val dm = campo.get(sdk) as? DeviceManager ?: return
            (dm.sgc as? MentraLive)?.refreshGlassesWifiStatus()
        } catch (e: Exception) {
            Log.w(TAG, "nao consegui pedir o status de Wi-Fi: ${e.message}")
        }
    }

    /** Pede aos oculos a lista de redes visiveis. Tambem e suspend. */
    fun escanearWifi() {
        scope.launch {
            val redes = runCatching { sdk.requestWifiScan() }.getOrNull()
            Log.d(TAG, "redes visiveis pelos oculos: ${if (redes == null) "sem resposta" else "recebidas"}")
        }
    }

    override fun onWifiStatusChanged(event: com.mentra.bluetoothsdk.WifiStatusEvent) {
        val st = event.status
        val ligado = st is WifiStatus.Connected
        val ssid = (st as? WifiStatus.Connected)?.ssid
        wifiConectado = ligado
        Log.d(TAG, "wifi dos oculos: conectado=$ligado ssid=$ssid")
        _eventos.tryEmit(GlassesEvent.Wifi(ligado, ssid))
    }

    /** Ultimo estado de Wi-Fi reportado pelos oculos. */
    var wifiConectado: Boolean = false
        private set

    /** Resultado real do upload da foto. */
    override fun onPhotoResponse(event: com.mentra.bluetoothsdk.PhotoResponseEvent) {
        val url = campoTexto(event, "uploadUrl", "url")
        val req = campoTexto(event, "requestId", "id") ?: ultimoRequestId.orEmpty()
        val erro = campoTexto(event, "error", "errorMessage", "message")
        Log.d(TAG, "photo_response: req=$req url=$url erro=$erro")
        if (erro != null) {
            _eventos.tryEmit(GlassesEvent.Erro("óculos não conseguiram enviar a foto: $erro"))
            return
        }
        if (req.isNotBlank() && capturasNoTablet.remove(req)) {
            // Quem confirma é o arquivo chegando no receptor do tablet.
            _eventos.tryEmit(GlassesEvent.Aviso("óculos terminaram a foto — esperando o arquivo no tablet"))
            return
        }
        _eventos.tryEmit(GlassesEvent.CapturaRemota(TipoEvidencia.FOTO, req, uploadUrl = url))
    }

    /** Guardado para casar o photo_response quando o evento nao trouxer o id. */
    private var ultimoRequestId: String? = null

    /** Capturas cujo JPEG foi endereçado ao TABLET (receptor local), e não ao webhook público. */
    private val capturasNoTablet = java.util.Collections.synchronizedSet(mutableSetOf<String>())

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

    // Callbacks do SDK (MentraBluetoothSdkListener)

    override fun onGlassesChanged(glasses: GlassesRuntimeState) {
        val estavaConectado = conectado
        conectado = glasses is GlassesRuntimeState.Connected
        // O SDK chama isto a CADA mudança de estado dos óculos (bateria, sinal, Wi-Fi, pronto) — não só quando conectam ou caem.
        if (glasses is GlassesRuntimeState.Connected) {
            val wifi = glasses.wifi as? WifiStatus.Connected
            if (wifi != null && !wifiConectado) {
                wifiConectado = true
                Log.d(TAG, "wifi dos oculos (pelo estado): ${wifi.ssid}")
                _eventos.tryEmit(GlassesEvent.Wifi(true, wifi.ssid))
            }
        }
        if (conectado == estavaConectado) return // só transições daqui para baixo
        if (!conectado) {
            tentandoConectar = false
            // BLE caiu: o que sabíamos da Wi-Fi DELES venceu junto.
            wifiConectado = false
            if (estavaConectado) {
                // Queda de uma ligação que estava de pé.
                val duracaoS = (System.currentTimeMillis() - conectadoDesdeMs) / 1000
                Log.w(TAG, "BLE caiu apos ${duracaoS}s conectado; estado=$glasses")
                if (!desligadoPeloPerito) agendarReconexao()
            }
        } else {
            tentativasAutomaticas = 0 // conectou: zera o contador de retentativas
            conectadoDesdeMs = System.currentTimeMillis()
            reconexao?.cancel(); reconexao = null
            // ativarAudioNosOculos() — desligado por ora: suspeita de derrubar a conexao BLE em alguns firmwares.
        }
        Log.d(TAG, "glasses: $glasses (conectado=$conectado)")
        _eventos.tryEmit(GlassesEvent.Conexao(conectado))
    }

    /** Liga o servico de fone Bluetooth (HFP) DOS OCULOS, para as confirmacoes faladas sairem no alto-falante deles. */
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
        // Diagnostico: o primeiro frame prova que o microfone DOS OCULOS esta mesmo transmitindo.
        if (framesPcmRecebidos == 20) {
            _eventos.tryEmit(GlassesEvent.Aviso("Microfone dos óculos transmitindo ✓"))
        }
        onPcm?.invoke(event.pcm, event.sampleRate)
    }

    override fun onError(error: com.mentra.bluetoothsdk.BluetoothError) {
        _eventos.tryEmit(GlassesEvent.Erro("SDK Mentra: $error"))
    }

    // Interface GlassesDevice

    fun capturarFotoComAutorizacao(autorizacao: AutorizacaoCaptura) {
        if (!conectado) {
            _eventos.tryEmit(GlassesEvent.Erro("óculos não conectado — toque em CONECTAR ÓCULOS"))
            return
        }
        if (streamAtivo) {
            _eventos.tryEmit(GlassesEvent.Erro("leia o lacre antes de iniciar o vídeo da sessão"))
            return
        }
        scope.launch {
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
                if (!wifiConectado) {
                    _eventos.tryEmit(
                        GlassesEvent.Erro("Foto do lacre disparada, mas os óculos estão SEM Wi-Fi — configure o Wi-Fi.")
                    )
                }
            } catch (e: Exception) {
                _eventos.tryEmit(GlassesEvent.Erro("falha ao fotografar o lacre: ${e.message}"))
            }
        }
    }

    override fun capturarFoto() {
        if (!conectado) {
            _eventos.tryEmit(GlassesEvent.Erro("óculos não conectado — toque em CONECTAR ÓCULOS"))
            return
        }
        // requestPhoto e suspend: sobe o JPEG por Wi-Fi ao webhook e so retorna quando termina.
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

            ultimoRequestId = autorizacao.requestId
            if (autorizacao.peloTablet) capturasNoTablet.add(autorizacao.requestId)
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
                // NAO emitimos CapturaRemota aqui: requestPhoto voltar sem erro significa apenas "comando aceito".
                if (!wifiConectado) {
                    _eventos.tryEmit(
                        GlassesEvent.Erro(
                            "Foto disparada, mas os óculos estão SEM Wi-Fi — " +
                                "a imagem não vai chegar ao servidor. Configure o Wi-Fi dos óculos."
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "requestPhoto falhou (stream ativo? $streamAtivo): ${e.message}")
                val cameraOcupada = e.message?.contains("busy", ignoreCase = true) == true
                if (cameraOcupada) {
                    // Recusa DEFINITIVA, não atraso: os óculos não vão mandar arquivo nenhum.
                    _eventos.tryEmit(
                        GlassesEvent.FotoRecusada(
                            autorizacao.requestId, e.message ?: "câmera ocupada com o vídeo",
                            autorizacao.webhookUrl, autorizacao.authToken,
                        )
                    )
                } else if (autorizacao.peloTablet) {
                    _eventos.tryEmit(
                        GlassesEvent.Aviso(
                            "os óculos não confirmaram a foto (${e.message}) — sigo esperando o arquivo no tablet",
                        )
                    )
                } else {
                    // Câmera ocupada com o stream, ou qualquer outra recusa: cai para a MARCA no vídeo, dizendo o que é.
                    _eventos.tryEmit(
                        GlassesEvent.CapturaRemota(TipoEvidencia.FOTO, autorizacao.requestId, null, fotoDeVerdade = false)
                    )
                    _eventos.tryEmit(
                        GlassesEvent.Aviso(
                            "os óculos não deram a foto (${e.message}) — marquei o quadro no vídeo; " +
                                "a imagem só entra no laudo se o servidor conseguir recortá-la",
                        )
                    )
                }
            }
        }
    }

    // VIDEO DA SESSAO — os oculos transmitem RTMP direto ao servidor (startStream).

    /** Perfil do stream. */
    data class PerfilVideo(val largura: Int, val altura: Int, val bitrate: Int, val fps: Int, val nome: String) {
        companion object {
            val SERVIDOR = PerfilVideo(960, 540, 1_200_000, 15, "540p · 15 fps · 1,2 Mbps")
            val TABLET_720P30 = PerfilVideo(1280, 720, 3_000_000, 30, "720p · 30 fps · 3 Mbps")
            val TABLET_1080P30 = PerfilVideo(1920, 1080, 5_000_000, 30, "1080p · 30 fps · 5 Mbps")

            /** Mesmo detalhe do perfil do servidor, movimento no dobro de quadros.
             *  Pede 2,5 Mbps pela internet: só com link de subida bom na bancada. */
            val SERVIDOR_FLUIDO = PerfilVideo(960, 540, 2_500_000, 30, "540p · 30 fps · 2,5 Mbps (fluido)")
            /** 720p fluido de verdade: 6 Mbps para os 30 fps não perderem nitidez. */
            val TABLET_720P_FLUIDO = PerfilVideo(1280, 720, 6_000_000, 30, "720p · 30 fps · 6 Mbps (fluido)")
            /** O mais fluido que os óculos dão. Exige Wi-Fi 5 GHz boa no tablet. */
            val TABLET_1080P_FLUIDO = PerfilVideo(1920, 1080, 8_000_000, 30, "1080p · 30 fps · 8 Mbps (fluido)")
        }
    }
    var perfilVideo: PerfilVideo = PerfilVideo.SERVIDOR

    private var streamAtivo = false
    /** Cresce a cada iniciarVideo. */
    private var geracaoVideo = 0
    private var tarefaIniciarVideo: kotlinx.coroutines.Job? = null

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
        streamAtivo = true
        geracaoVideo += 1
        tarefaIniciarVideo = scope.launch {
            try {
                _eventos.tryEmit(GlassesEvent.Aviso("iniciando vídeo dos óculos..."))
                sdk.startStream(
                    StreamRequest(
                        streamUrl = urlStream,
                        streamId = "pv-${System.currentTimeMillis()}",
                        sound = false,
                        video = StreamVideoConfig(width = perfilVideo.largura, height = perfilVideo.altura, bitrate = perfilVideo.bitrate, fps = perfilVideo.fps),
                    )
                )
                _eventos.tryEmit(GlassesEvent.GravacaoIniciada(TipoEvidencia.VIDEO))
            } catch (e: Exception) {
                streamAtivo = false
                _eventos.tryEmit(GlassesEvent.Erro("vídeo dos óculos: ${e.message}"))
            }
        }
    }

    override fun pararVideo() {
        if (!streamAtivo) return
        streamAtivo = false
        // Cancela um startStream ainda em negociação: senão ele termina depois do stop e deixa o stream de pé.
        tarefaIniciarVideo?.cancel()
        val minhaGeracao = geracaoVideo
        scope.launch {
            // Os óculos às vezes perdem o primeiro stop — insiste até 3 vezes.
            var parou = false
            for (tentativa in 1..3) {
                if (geracaoVideo != minhaGeracao) {
                    Log.w(TAG, "stopStream abortado: o vídeo já foi religado (geração ${geracaoVideo})")
                    return@launch
                }
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

    // COMANDO DE VOZ pelos MICROFONES DOS OCULOS (transcricao local)

    /** Chamado quando os oculos ouvem uma palavra-chave de captura. */
    var onComandoVoz: (() -> Unit)? = null

    /** Palavras que disparam a captura. */
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
                // Mic OK, transcricao embarcada nao.
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
        Log.d(TAG, "transcricao #$transcricoesRecebidas: ${texto?.length ?: 0} caracteres")
        if (texto.isNullOrBlank()) {
            // Chegou evento mas sem texto legivel: mostra o cru para diagnostico.
            _eventos.tryEmit(GlassesEvent.Aviso("Óculos ouviram algo (sem texto): $event"))
            return
        }
        // Mostra SEMPRE o que os oculos entenderam — assim da para ver se ele ouve mas erra a palavra, ou se nao ouve nada.
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
        desligadoPeloPerito = true
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
