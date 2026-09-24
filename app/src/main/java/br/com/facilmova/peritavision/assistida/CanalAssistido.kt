// Perícia assistida remota, lado do campo: vídeo do visor e voz dos óculos vão à perita
// oficial por WebRTC; a voz dela volta e toca no alto-falante dos óculos (A2DP).
// O servidor só sinaliza (SDP/ICE) e guarda instruções e confirmações na trilha.
package br.com.facilmova.peritavision.assistida

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.CapturerObserver
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/** Servidor STUN/TURN entregue pelo backend. */
data class ServidorIce(val urls: List<String>, val usuario: String?, val senha: String?) {
    companion object {
        fun deJson(arr: JSONArray?): List<ServidorIce> {
            if (arr == null) return emptyList()
            val lista = ArrayList<ServidorIce>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val urls = when (val u = o.opt("urls")) {
                    is String -> listOf(u)
                    is JSONArray -> (0 until u.length()).map { u.optString(it) }.filter { it.isNotBlank() }
                    else -> emptyList()
                }
                if (urls.isEmpty()) continue
                lista += ServidorIce(urls, o.optString("username").ifBlank { null }, o.optString("credential").ifBlank { null })
            }
            return lista
        }
    }
}

class CanalAssistido(
    private val context: Context,
    private val urlSinal: String,
    private val jwt: String,
    /** Um quadro do visor ao vivo, já reduzido (≤ 960 px de largura); null = sem imagem. */
    private val quadroDoVisor: suspend () -> Bitmap?,
) {
    var onStatus: (String) -> Unit = {}
    var onPeritaOnline: (Boolean) -> Unit = {}
    var onInstrucao: (id: String, texto: String) -> Unit = { _, _ -> }
    var onFoto: () -> Unit = {}
    var onMarcacao: (x: Float, y: Float) -> Unit = { _, _ -> }
    var onEncerrada: () -> Unit = {}

    /** Microfone do campo silenciado pelo perito. */
    @Volatile var mudo = false

    @Volatile private var encerrado = false
    @Volatile private var peritaOnline = false
    /** Até quando a voz da perita está saindo nos óculos: o microfone fica mudo nesse tempo. */
    @Volatile private var peritaFalandoAteMs = 0L

    private val principal = Handler(Looper.getMainLooper())
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cliente = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    @Volatile private var ws: WebSocket? = null
    private var reconexao: Job? = null
    private var tentativas = 0
    private var ice: List<ServidorIce> = emptyList()

    private var egl: EglBase? = null
    private var fabrica: PeerConnectionFactory? = null
    private var par: PeerConnection? = null
    private var canalVoz: DataChannel? = null
    private var fonteVideo: VideoSource? = null
    private var trilhaVideo: VideoTrack? = null
    private var capturador: CapturadorDoVisor? = null
    private var helper: SurfaceTextureHelper? = null

    @Volatile private var track: AudioTrack? = null

    // ------------------------------------------------------------------ sinalização
    fun conectar() {
        if (encerrado) return
        val pedido = Request.Builder().url(urlSinal).addHeader("Authorization", "Bearer $jwt").build()
        ws = cliente.newWebSocket(pedido, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                tentativas = 0
                aviso("Sala conectada — aguardando a perita")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (m.optString("t")) {
                    "pronto" -> {
                        ice = ServidorIce.deJson(m.optJSONArray("ice"))
                        definirPeritaOnline(m.optBoolean("outroOnline"))
                    }
                    "presenca" -> if (m.optString("papel") == "perita") definirPeritaOnline(m.optBoolean("online"))
                    "resposta" -> receberResposta(m.optJSONObject("sdp")?.optString("sdp") ?: return)
                    "ice" -> m.optJSONObject("candidato")?.let { c ->
                        par?.addIceCandidate(IceCandidate(c.optString("sdpMid", null), c.optInt("sdpMLineIndex", 0), c.optString("candidate")))
                    }
                    "instrucao" -> principal.post { onInstrucao(m.optString("id"), m.optString("texto")) }
                    "foto" -> principal.post { onFoto() }
                    "marcacao" -> principal.post { onMarcacao(m.optDouble("x", 0.5).toFloat(), m.optDouble("y", 0.5).toFloat()) }
                    "encerrada" -> { encerrado = true; principal.post { onEncerrada() }; fechar() }
                    "erro" -> aviso("Sala: ${m.optString("codigo")}")
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "sinalização caiu: ${t.message}")
                if (response?.code == 401 || response?.code == 403) { aviso("Sala recusada: sem autorização"); return }
                agendarReconexao()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!encerrado && reason != "substituida") agendarReconexao()
            }
        })
    }

    private fun agendarReconexao() {
        if (encerrado || reconexao?.isActive == true) return
        definirPeritaOnline(false)
        reconexao = escopo.launch {
            val espera = minOf(30_000L, 2_000L * (1L shl minOf(tentativas, 4)))
            tentativas++
            delay(espera)
            if (!encerrado) conectar()
        }
    }

    private fun enviarJson(o: JSONObject) { ws?.send(o.toString()) }

    private fun aviso(texto: String) { principal.post { onStatus(texto) } }

    private fun definirPeritaOnline(online: Boolean) {
        val mudou = online != peritaOnline
        peritaOnline = online
        principal.post { onPeritaOnline(online) }
        if (!online) { fecharPar(); return }
        if (mudou || par == null) escopo.launch { oferecer() }
    }

    fun feito(id: String, ok: Boolean, texto: String? = null) {
        enviarJson(JSONObject().put("t", "feito").put("id", id).put("ok", ok).apply { if (texto != null) put("texto", texto) })
    }

    fun estado(fotos: Int, bateria: Int?) {
        enviarJson(JSONObject().put("t", "estado").put("fotos", fotos).apply { if (bateria != null) put("bateria", bateria) })
    }

    // ------------------------------------------------------------------ WebRTC
    private fun garantirFabrica(): PeerConnectionFactory {
        fabrica?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions(),
        )
        val base = EglBase.create().also { egl = it }
        return PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(base.eglBaseContext, true, false))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(base.eglBaseContext))
            .createPeerConnectionFactory()
            .also { fabrica = it }
    }

    private fun servidoresIce(): List<PeerConnection.IceServer> = ice.map { s ->
        PeerConnection.IceServer.builder(s.urls).apply {
            if (s.usuario != null) setUsername(s.usuario)
            if (s.senha != null) setPassword(s.senha)
        }.createIceServer()
    }

    private fun oferecer() {
        if (encerrado) return
        fecharPar()
        val f = garantirFabrica()
        val config = PeerConnection.RTCConfiguration(servidoresIce()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = f.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                enviarJson(JSONObject().put("t", "ice").put("candidato",
                    JSONObject().put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex)))
            }
            override fun onConnectionChange(estado: PeerConnection.PeerConnectionState) {
                when (estado) {
                    PeerConnection.PeerConnectionState.CONNECTED -> aviso("Perita conectada — vídeo e voz no ar")
                    PeerConnection.PeerConnectionState.FAILED -> { aviso("Ligação com a perita falhou — tentando de novo"); if (peritaOnline) escopo.launch { delay(2_000); oferecer() } }
                    else -> {}
                }
            }
            override fun onDataChannel(dc: DataChannel) {}
            override fun onSignalingChange(p0: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>) {}
            override fun onAddStream(p0: MediaStream) {}
            override fun onRemoveStream(p0: MediaStream) {}
            override fun onRenegotiationNeeded() {}
        }) ?: run { aviso("WebRTC indisponível neste tablet"); return }
        par = pc

        // vídeo: quadros do visor desenhados numa textura do WebRTC
        val h = SurfaceTextureHelper.create("pv-assistida", egl!!.eglBaseContext).also { helper = it }
        val fonte = f.createVideoSource(false).also { fonteVideo = it }
        val cap = CapturadorDoVisor(quadroDoVisor, escopo).also { capturador = it }
        cap.initialize(h, context, fonte.capturerObserver)
        cap.startCapture(LARGURA, ALTURA, FPS)
        val trilha = f.createVideoTrack("pv-visor", fonte).also { trilhaVideo = it }
        pc.addTrack(trilha, listOf("pv"))

        // voz: PCM 16 kHz nos dois sentidos, sem reenvio (tempo real vale mais que quadro perdido)
        val dc = pc.createDataChannel("voz", DataChannel.Init().apply { ordered = false; maxRetransmits = 0 })
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {}
            override fun onStateChange() { if (dc.state() == DataChannel.State.OPEN) aviso("Canal de voz aberto") }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val b = buffer.data
                val pcm = ByteArray(b.remaining()).also { b.get(it) }
                tocarVozDaPerita(pcm)
            }
        })
        canalVoz = dc

        pc.createOffer(object : SdpObserverSimples() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(object : SdpObserverSimples() {
                    override fun onSetSuccess() {
                        enviarJson(JSONObject().put("t", "oferta").put("sdp", JSONObject().put("type", "offer").put("sdp", sdp.description)))
                    }
                }, sdp)
            }
            override fun onCreateFailure(erro: String?) { aviso("Não consegui montar a oferta de vídeo") }
        }, MediaConstraints())
    }

    private fun receberResposta(sdp: String) {
        par?.setRemoteDescription(SdpObserverSimples(), SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    private fun fecharPar() {
        runCatching { capturador?.stopCapture() }
        runCatching { capturador?.dispose() }
        capturador = null
        runCatching { canalVoz?.close(); canalVoz?.dispose() }
        canalVoz = null
        runCatching { trilhaVideo?.dispose() }
        trilhaVideo = null
        runCatching { fonteVideo?.dispose() }
        fonteVideo = null
        runCatching { helper?.dispose() }
        helper = null
        runCatching { par?.close(); par?.dispose() }
        par = null
    }

    // ------------------------------------------------------------------ áudio
    /** PCM16 16 kHz do microfone dos óculos. Enquanto a perita fala, nada sobe (evita o eco). */
    fun enviarPcm(pcm: ByteArray) {
        if (mudo || encerrado) return
        if (System.currentTimeMillis() < peritaFalandoAteMs + CAUDA_MS) return
        val dc = canalVoz ?: return
        if (dc.state() != DataChannel.State.OPEN || dc.bufferedAmount() > 64 * 1024) return
        dc.send(DataChannel.Buffer(ByteBuffer.wrap(pcm), true))
    }

    private fun criarTrack(): AudioTrack {
        val minimo = AudioTrack.getMinBufferSize(TAXA_HZ, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(TAXA_HZ)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minimo * 4, TAXA_HZ * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }

    private fun tocarVozDaPerita(pcm: ByteArray) {
        if (encerrado || pcm.isEmpty()) return
        val t = track ?: runCatching { criarTrack() }.getOrNull()?.also { track = it } ?: return
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) runCatching { t.play() }
        val duracaoMs = (pcm.size / 2L) * 1000L / TAXA_HZ
        peritaFalandoAteMs = maxOf(peritaFalandoAteMs, System.currentTimeMillis()) + duracaoMs
        val r = runCatching { t.write(pcm, 0, pcm.size) }.getOrDefault(AudioTrack.ERROR_DEAD_OBJECT)
        if (r == AudioTrack.ERROR_DEAD_OBJECT) {
            runCatching { t.release() }
            track = null
        }
    }

    // ------------------------------------------------------------------ fim
    fun encerrar(avisarServidor: Boolean) {
        if (encerrado) return
        encerrado = true
        if (avisarServidor) runCatching { ws?.send(JSONObject().put("t", "encerrar").toString()) }
        fechar()
    }

    private fun fechar() {
        reconexao?.cancel()
        escopo.cancel()
        fecharPar()
        runCatching { ws?.close(1000, "encerrado pelo app") }
        ws = null
        runCatching { track?.stop(); track?.release() }
        track = null
        runCatching { fabrica?.dispose() }
        fabrica = null
        runCatching { egl?.release() }
        egl = null
    }

    private open class SdpObserverSimples : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) { Log.w(TAG, "sdp: $p0") }
        override fun onSetFailure(p0: String?) { Log.w(TAG, "sdp: $p0") }
    }

    /** Desenha os quadros do visor na textura do WebRTC, na cadência pedida. */
    private class CapturadorDoVisor(
        private val quadro: suspend () -> Bitmap?,
        private val escopo: CoroutineScope,
    ) : VideoCapturer {
        private var helper: SurfaceTextureHelper? = null
        private var observador: CapturerObserver? = null
        private var superficie: Surface? = null
        private var laco: Job? = null

        override fun initialize(helper: SurfaceTextureHelper, context: Context, observer: CapturerObserver) {
            this.helper = helper
            this.observador = observer
        }

        override fun startCapture(largura: Int, altura: Int, fps: Int) {
            val h = helper ?: return
            h.setTextureSize(largura, altura)
            superficie = Surface(h.surfaceTexture)
            h.startListening { frame -> observador?.onFrameCaptured(frame) }
            observador?.onCapturerStarted(true)
            val intervalo = 1000L / fps.coerceAtLeast(1)
            laco = escopo.launch {
                while (isActive) {
                    val inicio = System.currentTimeMillis()
                    val bmp = runCatching { quadro() }.getOrNull()
                    if (bmp != null) {
                        val s = superficie
                        if (s != null && s.isValid) {
                            runCatching {
                                val canvas = s.lockCanvas(null)
                                canvas.drawBitmap(bmp, null, Rect(0, 0, largura, altura), null)
                                s.unlockCanvasAndPost(canvas)
                            }
                        }
                        bmp.recycle()
                    }
                    delay((intervalo - (System.currentTimeMillis() - inicio)).coerceAtLeast(5L))
                }
            }
        }

        override fun stopCapture() {
            laco?.cancel()
            laco = null
            helper?.stopListening()
            observador?.onCapturerStopped()
            superficie?.release()
            superficie = null
        }

        override fun changeCaptureFormat(largura: Int, altura: Int, fps: Int) {
            helper?.setTextureSize(largura, altura)
        }

        override fun dispose() { stopCapture() }
        override fun isScreencast(): Boolean = false
    }

    companion object {
        private const val TAG = "PV-Assistida"
        const val TAXA_HZ = 16_000
        private const val CAUDA_MS = 300L
        private const val LARGURA = 960
        private const val ALTURA = 540
        private const val FPS = 10
    }
}
