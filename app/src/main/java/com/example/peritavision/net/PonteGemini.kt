package com.example.peritavision.net

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/**
 * PONTE DE BANCADA COM O GEMINI LIVE — protótipo da Frente 5 do roadmap.
 *
 * Fala com o servidor-continuo.mjs (pasta ponte-gemini-live, roda no PC da
 * bancada) pelo protocolo combinado lá:
 *   → texto JSON  {"tipo":"iniciar","sessaoId":...}   abre a sessão
 *   → binário     0x01 + PCM16/16kHz                  áudio do perito
 *   ← texto JSON  {"tipo":"transcricaoEntrada"|"textoResposta"|"pronto"|"erro"}
 *   ← binário     0x03 + PCM16/24kHz                  voz de resposta do Gemini
 *
 * A voz de resposta toca num AudioTrack em modo stream — se os óculos estiverem
 * pareados como áudio Bluetooth, o Android roteia para o alto-falante deles,
 * igual já acontece com o FeedbackDeVoz.
 *
 * ── CUSTÓDIA (leia antes de ligar em caso real) ─────────────────────────────
 * Com a ponte ATIVA, uma cópia do áudio do microfone dos óculos sai do
 * circuito local e vai aos servidores do Google (é a natureza do Gemini Live).
 * O caminho oficial (reconhecimento offline na máquina da POLITEC) continua
 * funcionando em paralelo e não é alterado. Desde 27/08/2026 a ponte liga
 * AUTOMATICAMENTE quando a sessão de bancada abre (pedido do Brunno: "no
 * óculos tem que funcionar no automático") — o perito ainda pode desligar
 * pelo cartão, e o desligamento manual vale até a próxima sessão. Continua
 * NÃO devendo ser usada em caso real até a decisão de custódia estar
 * documentada com a POLITEC (ROADMAP_ALIQUOTAGEM.md, "Custódia e
 * conformidade").
 */
class PonteGemini(
    private val url: String,
    private val sessaoId: String,
) {
    var onTranscricao: (String) -> Unit = {}
    var onResposta: (String) -> Unit = {}
    var onStatus: (String) -> Unit = {}
    /** Chamado quando o servidor confirma que o vídeo dos óculos chegou ao
     *  Gemini — só então o assistente realmente ENXERGA a bancada. */
    var onVideoAtivo: () -> Unit = {}

    @Volatile private var pronto = false
    /** true depois de encerrar(): a reconexão automática para de tentar. */
    @Volatile private var encerrado = false
    /** Guardados até o "pronto" (e reenviados após reconexão automática). */
    @Volatile private var urlVideo: String? = null
    @Volatile private var contextoCaso: String? = null
    private var ws: WebSocket? = null
    private val cliente = OkHttpClient()
    private val principal = Handler(Looper.getMainLooper())

    /** Toca PCM16 mono 24 kHz conforme chega — sem esperar a resposta inteira. */
    private val track: AudioTrack by lazy {
        val minimo = AudioTrack.getMinBufferSize(
            24_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(24_000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minimo * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }

    fun conectar() {
        encerrado = false
        val pedido = Request.Builder().url(url).build()
        ws = cliente.newWebSocket(pedido, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    JSONObject().put("tipo", "iniciar").put("sessaoId", sessaoId).toString(),
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (msg.optString("tipo")) {
                    "pronto" -> {
                        pronto = true
                        onStatus("Assistente IA pronto — pode falar.")
                        // O que ficou guardado antes da sessão abrir vai agora
                        // (e vai DE NOVO a cada reconexão — sessão nova no
                        // Gemini não lembra da anterior).
                        contextoCaso?.let { enviarJson("contexto", "texto", it) }
                        urlVideo?.let { enviarJson("video", "url", it) }
                    }
                    "transcricaoEntrada" -> onTranscricao(msg.optString("texto"))
                    "textoResposta" -> onResposta(msg.optString("texto"))
                    "videoAtivo" -> onVideoAtivo()
                    "erro" -> onStatus("Assistente IA: ${msg.optString("mensagem")}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // 0x03 = voz de resposta. O write é bloqueante, mas roda na
                // thread do OkHttp, não na UI.
                if (bytes.size > 1 && bytes[0] == 0x03.toByte()) {
                    val pcm = bytes.substring(1).toByteArray()
                    runCatching { track.write(pcm, 0, pcm.size) }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                pronto = false
                Log.w(TAG, "ponte caiu: ${t.message}")
                // AUTOMÁTICO: rede de bancada pisca, servidor reinicia — a
                // ponte se reergue sozinha em vez de esperar um toque. Só o
                // encerrar() explícito (fim de sessão / desligado à mão) para
                // as tentativas.
                if (!encerrado) {
                    onStatus("Assistente IA caiu (${t.message ?: "falha de rede"}) — reconectando...")
                    principal.postDelayed({ if (!encerrado) conectar() }, 3_000)
                } else {
                    onStatus("Assistente IA desconectado.")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                pronto = false
                onStatus("Assistente IA encerrado.")
            }
        })
    }

    private fun enviarJson(tipo: String, campo: String, valor: String) {
        runCatching { ws?.send(JSONObject().put("tipo", tipo).put(campo, valor).toString()) }
    }

    /** URL do FLV dos óculos (a MESMA da tela "Visão dos óculos"): o servidor
     *  puxa ~1 quadro/s dela e o Gemini passa a VER a bancada. */
    fun definirVideo(url: String?) {
        urlVideo = url
        if (pronto && url != null) enviarJson("video", "url", url)
    }

    /** Ficha do caso (protocolo, solicitante, vítima, materiais...) para o
     *  assistente responder perguntas sobre o caso. */
    fun definirContexto(texto: String?) {
        contextoCaso = texto
        if (pronto && texto != null) enviarJson("contexto", "texto", texto)
    }

    /** Cópia do PCM16/16kHz dos óculos. Barato: se a ponte não está pronta, ignora. */
    fun enviarPcm(pcm: ByteArray) {
        if (!pronto) return
        val quadro = ByteArray(pcm.size + 1)
        quadro[0] = 0x01
        System.arraycopy(pcm, 0, quadro, 1, pcm.size)
        ws?.send(quadro.toByteString())
    }

    fun encerrar() {
        encerrado = true
        pronto = false
        principal.removeCallbacksAndMessages(null)
        runCatching { ws?.close(1000, "encerrado pelo app") }
        runCatching { track.stop(); track.release() }
    }

    companion object { private const val TAG = "PonteGemini" }
}
