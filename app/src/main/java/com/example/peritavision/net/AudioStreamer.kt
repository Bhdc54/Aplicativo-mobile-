package com.example.peritavision.net

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Transmite o áudio DOS ÓCULOS ao backend PeritaVision e recebe os comandos
 * de voz reconhecidos.
 */
class AudioStreamer(
    private val baseUrl: String,
    private val token: String,
    private val sessaoId: String,
) {
    /** Chamado quando o backend reconhece um comando (CAPTURAR, MARCAR...). */
    var onComando: ((intencao: String, texto: String) -> Unit)? = null

    /** Mensagens de estado para a tela mostrar. */
    var onStatus: ((String) -> Unit)? = null

    private val cliente = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)   // WS fica aberto indefinidamente
        .pingInterval(20, TimeUnit.SECONDS)      // mantém viva a conexão
        .build()

    private var ws: WebSocket? = null
    private var conectado = false
    private var framesEnviados = 0L

    // Segmentação contínua da narração
    // O backend guarda tudo que chega até um "marcador" e então consolida um
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var ultimoFrameComSomMs = 0L
    private var haAudioDesdeUltimoMarcador = false
    private val PAUSA_PARA_FECHAR_TRECHO_MS = 1500L
    private val verificadorDePausa = object : Runnable {
        override fun run() {
            if (conectado && haAudioDesdeUltimoMarcador &&
                System.currentTimeMillis() - ultimoFrameComSomMs >= PAUSA_PARA_FECHAR_TRECHO_MS
            ) {
                marcar()
                haAudioDesdeUltimoMarcador = false
            }
            handler.postDelayed(this, 500)
        }
    }

    fun conectar() {
        if (ws != null) return
        // http:// → ws://   e   https:// → wss://
        val url = baseUrl.trimEnd('/').replaceFirst(Regex("^http"), "ws") +
            "/v1/sessoes/$sessaoId/audio"
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .build()

        ws = cliente.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                conectado = true
                Log.d(TAG, "WS de áudio aberto: $url")
                onStatus?.invoke("Escuta ativa nos óculos")
                handler.post(verificadorDePausa)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val j = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (j.optString("t")) {
                    "comando" -> {
                        val intencao = j.optString("intencao")
                        val ouvido = j.optString("texto")
                        Log.d(TAG, "comando: $intencao (\"$ouvido\")")
                        if (intencao.isNotBlank()) onComando?.invoke(intencao, ouvido)
                    }
                    "final" -> onStatus?.invoke("Narração: ${j.optString("texto").take(60)}")
                    "erro" -> onStatus?.invoke(
                        "Serviço de voz indisponível — verifique se o ASR está rodando"
                    )
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                conectado = false
                Log.w(TAG, "WS de áudio caiu: ${t.message}")
                onStatus?.invoke("Escuta interrompida: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                conectado = false
            }
        })
    }

    /** Envia um frame PCM vindo do microfone dos óculos. */
    fun enviarPcm(pcm: ByteArray) {
        if (!conectado) return
        ws?.send(pcm.toByteString())
        framesEnviados++
        if (framesEnviados == 50L) onStatus?.invoke("Áudio dos óculos chegando ao servidor ✓")

        if (temFala(pcm)) {
            ultimoFrameComSomMs = System.currentTimeMillis()
            haAudioDesdeUltimoMarcador = true
        }
    }

    /**
     * Amplitude simples (RMS) do frame PCM 16-bit. Não é VAD sofisticado, só
     * o suficiente para distinguir "o perito está falando" de "silêncio
     */
    private fun temFala(pcm: ByteArray): Boolean {
        if (pcm.size < 2) return false
        var soma = 0.0
        var i = 0
        while (i + 1 < pcm.size) {
            val amostra = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()
            soma += amostra * amostra
            i += 2
        }
        val rms = kotlin.math.sqrt(soma / (pcm.size / 2))
        return rms > LIMIAR_RUIDO
    }

    /** Marca o fim de um trecho de narração (o backend consolida e persiste). */
    fun marcar() {
        if (!conectado) return
        ws?.send(JSONObject().put("t", "marcador").toString())
    }

    fun encerrar() {
        handler.removeCallbacks(verificadorDePausa)
        runCatching { ws?.close(1000, "fim da sessão") }
        ws = null
        conectado = false
        framesEnviados = 0
    }

    companion object {
        private const val TAG = "AudioStreamer"
        /** Amplitude RMS mínima para considerar "tem fala". Calibrado empírico. */
        private const val LIMIAR_RUIDO = 350.0
    }
}
