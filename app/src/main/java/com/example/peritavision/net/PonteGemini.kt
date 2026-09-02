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
import com.example.peritavision.data.CatalogoPonte
import com.example.peritavision.data.TrilhaCatalogo

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
    /** Modelo Gemini Live escolhido em Configurações; vazio = padrão da ponte. */
    private val modelo: String = "",
    /** Trilha fixada em Configurações (id do catálogo); null = a IA pergunta
     *  ao perito na abertura ("objeto cortante ou peça íntima?"). */
    private val trilha: String? = null,
) {
    var onTranscricao: (String) -> Unit = {}
    /** A ponte abriu a sessão de TRIAGEM: a IA vai perguntar a trilha. */
    var onTriagem: () -> Unit = {}
    /** Trilha definida (id, nome, origem 'perito'|'app'|'memoria'): a sessão
     *  de trabalho está de pé com o roteiro certo. */
    var onTrilha: (id: String, nome: String, origem: String) -> Unit = { _, _, _ -> }
    var onResposta: (String) -> Unit = {}
    var onStatus: (String) -> Unit = {}
    /** Chamado quando o servidor confirma que o vídeo dos óculos chegou ao
     *  Gemini — só então o assistente realmente ENXERGA a bancada. */
    var onVideoAtivo: () -> Unit = {}
    /** Janela de visão abriu/fechou: a IA só OLHA quando o perito pede. */
    var onVisao: (Boolean) -> Unit = {}
    /** O Gemini pediu uma função de bancada (capturar_foto, finalizar_sessao).
     *  Quem executa é o app; responda com responderComando(id, nome, ...). */
    var onComando: (id: String, nome: String) -> Unit = { _, _ -> }

    @Volatile private var pronto = false
    /** true depois de encerrar(): a reconexão automática para de tentar. */
    @Volatile private var encerrado = false
    /** Até quando a voz do assistente está tocando no alto-falante (ms epoch).
     *  Base do HALF-DUPLEX anti-eco: enquanto ele fala, o microfone fica
     *  surdo — sem isto a voz dele voltava pelo mic dos óculos, ele se ouvia
     *  e não parava de falar; e o "fotografe" falado por ele disparava o
     *  comando de captura offline. */
    @Volatile private var falandoAteMs = 0L
    /** Guardados até o "pronto" (e reenviados após reconexão automática). */
    @Volatile private var urlVideo: String? = null
    @Volatile private var contextoCaso: String? = null
    private var ws: WebSocket? = null
    private val cliente = OkHttpClient()
    private val principal = Handler(Looper.getMainLooper())

    /** Toca PCM16 mono 24 kHz conforme chega — sem esperar a resposta inteira. */
    @Volatile private var track: AudioTrack? = null

    private fun criarTrack(): AudioTrack {
        val minimo = AudioTrack.getMinBufferSize(
            24_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioTrack.Builder()
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

    /** Marca de tempo do último trecho de voz recebido — serve para saber
     *  onde uma fala termina e outra começa (silêncio > 1,2 s). */
    @Volatile private var ultimoAudioMs = 0L

    /** Escreve no AudioTrack mantendo a saída VIVA entre uma fala e outra.
     *
     *  Sintoma que isto resolve: depois de 3-4 respostas, a IA continuava
     *  escrevendo no tablet mas não saía voz nenhuma nos óculos — justamente
     *  quando o perito pergunta "o que eu tenho na mão", que é o uso real.
     *
     *  São TRÊS falhas diferentes, todas silenciosas, tratadas aqui:
     *   1. O track morre quando a rota Bluetooth pisca (ERROR_DEAD_OBJECT):
     *      todo write seguinte falha sem exceção. → recria e reescreve.
     *   2. O track sai do estado PLAYING (pausado por foco de áudio, por uma
     *      fala do TTS, pelo player do monitor): os writes ENTRAM no buffer e
     *      não tocam nada. → confere playState e chama play() de novo.
     *   3. A rota A2DP dos óculos entra em baixo consumo entre uma fala e
     *      outra; o track continua "vivo e tocando", mas o som não chega ao
     *      alto-falante. Não há erro nenhum para detectar. → a cada NOVA fala
     *      (silêncio de mais de 1,2 s, quando o áudio anterior já escoou por
     *      completo) a saída é recriada do zero, garantindo rota nova. */
    private fun tocar(pcm: ByteArray) {
        val agora = System.currentTimeMillis()
        val novaFala = agora - ultimoAudioMs > 1_200L
        ultimoAudioMs = agora

        if (novaFala) {
            // Seguro: o intervalo garante que a fala anterior já terminou de
            // sair, então nada é cortado ao trocar de track.
            track?.let { antigo -> runCatching { antigo.stop(); antigo.release() } }
            track = null
        }

        var t = track ?: runCatching { criarTrack() }.getOrNull()?.also { track = it } ?: return
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) {
            Log.w(TAG, "AudioTrack não estava tocando (estado ${t.playState}) — religando")
            runCatching { t.play() }
        }
        val r = runCatching { t.write(pcm, 0, pcm.size) }.getOrDefault(AudioTrack.ERROR_DEAD_OBJECT)
        if (r < 0) {
            Log.w(TAG, "AudioTrack morto (código $r) — recriando a saída de voz")
            runCatching { t.release() }
            t = runCatching { criarTrack() }.getOrNull()?.also { track = it } ?: return
            runCatching { t.write(pcm, 0, pcm.size) }
        }
    }

    fun conectar() {
        encerrado = false
        val pedido = Request.Builder().url(url).build()
        ws = cliente.newWebSocket(pedido, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val iniciar = JSONObject().put("tipo", "iniciar").put("sessaoId", sessaoId)
                if (modelo.isNotBlank()) iniciar.put("modelo", modelo)
                if (!trilha.isNullOrBlank()) iniciar.put("trilha", trilha)
                webSocket.send(iniciar.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== ws) return // socket antigo
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (msg.optString("tipo")) {
                    "pronto" -> {
                        pronto = true
                        val etapa = msg.optString("etapa")
                        onStatus(
                            if (etapa == "triagem") "Assistente IA pronto — vai perguntar o tipo de exame."
                            else "Assistente IA pronto — pode falar.",
                        )
                        // O que ficou guardado antes da sessão abrir vai agora
                        // (e vai DE NOVO a cada reconexão — sessão nova no
                        // Gemini não lembra da anterior).
                        contextoCaso?.let { enviarJson("contexto", "texto", it) }
                        urlVideo?.let { enviarJson("video", "url", it) }
                    }
                    "transcricaoEntrada" -> onTranscricao(msg.optString("texto"))
                    "textoResposta" -> onResposta(msg.optString("texto"))
                    "videoAtivo" -> onVideoAtivo()
                    "visao" -> onVisao(msg.optBoolean("ativa"))
                    "triagem" -> onTriagem()
                    "trilha" -> onTrilha(msg.optString("trilha"), msg.optString("nome"), msg.optString("origem"))
                    "comando" -> onComando(msg.optString("id"), msg.optString("nome"))
                    "erro" -> onStatus("Assistente IA: ${msg.optString("mensagem")}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (webSocket !== ws) return // socket antigo
                // 0x03 = voz de resposta. O write é bloqueante, mas roda na
                // thread do OkHttp, não na UI.
                if (bytes.size > 1 && bytes[0] == 0x03.toByte()) {
                    val pcm = bytes.substring(1).toByteArray()
                    // Estende a janela de "estou falando" pela duração deste
                    // trecho (PCM16 mono 24 kHz → bytes/2/24000 segundos),
                    // com uma cauda de 400 ms para o som acabar de sair.
                    val duracaoMs = (pcm.size / 2L) * 1000L / 24_000L
                    val agora = System.currentTimeMillis()
                    // Empilha só a duração real do trecho: a cauda de 400 ms
                    // é aplicada UMA vez em estaFalando(). Somar 400 ms por
                    // pacote deixava o microfone mudo por dezenas de segundos
                    // depois da fala — e as perguntas do perito se perdiam.
                    falandoAteMs = maxOf(falandoAteMs, agora) + duracaoMs
                    tocar(pcm)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Socket ANTIGO avisando que morreu depois de já existir uma
                // conexão nova: ignorar. Sem isto, o servidor derruba a
                // conexão velha (anti-zumbi), o velho cai aqui, reconecta,
                // o servidor derruba a "nova velha"... pingue-pongue infinito
                // que fechava a transmissão no meio da perícia.
                if (webSocket !== ws) return
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
                if (webSocket !== ws) return // socket antigo — já há conexão nova
                pronto = false
                // Fechamento LIMPO também reconecta: um redeploy da ponte no
                // servidor encerra a conexão educadamente (não é "falha"), e
                // sem isto o assistente ficava morto até religar na mão.
                // Só o encerrar() explícito (fim de sessão) para de tentar.
                if (!encerrado) {
                    onStatus("Assistente IA: servidor reiniciou — reconectando...")
                    principal.postDelayed({ if (!encerrado) conectar() }, 3_000)
                } else {
                    onStatus("Assistente IA encerrado.")
                }
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

    /** Resultado de uma função de bancada — o Gemini espera isto para
     *  confirmar por voz ("capturado"). */
    fun responderComando(id: String, nome: String, ok: Boolean, detalhe: String) {
        runCatching {
            ws?.send(
                JSONObject().put("tipo", "comandoResultado").put("id", id)
                    .put("nome", nome).put("ok", ok).put("detalhe", detalhe).toString(),
            )
        }
    }

    /** true enquanto a voz do assistente ainda está saindo no alto-falante. */
    fun estaFalando(): Boolean = System.currentTimeMillis() < falandoAteMs + 400L

    /** Cópia do PCM16/16kHz dos óculos. Barato: se a ponte não está pronta, ignora.
     *  HALF-DUPLEX: enquanto o assistente fala, o mic não sobe — ele não se ouve. */
    fun enviarPcm(pcm: ByteArray) {
        if (!pronto || estaFalando()) return
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
        runCatching { track?.stop(); track?.release() }
    }

    companion object {
        private const val TAG = "PonteGemini"

        /**
         * Busca o CATÁLOGO da ponte (trilhas e modelos) para a aba
         * Configurações: abre uma conexão curta, manda {tipo:'catalogo'},
         * lê a resposta e fecha. Não abre sessão Gemini. `aoTerminar` recebe
         * null se a ponte não respondeu (fora do alcance, URL vazia...).
         */
        fun buscarCatalogo(url: String, aoTerminar: (CatalogoPonte?) -> Unit) {
            if (url.isBlank()) { aoTerminar(null); return }
            val cliente = OkHttpClient.Builder()
                .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            var respondeu = false
            val terminar = { c: CatalogoPonte? ->
                if (!respondeu) { respondeu = true; aoTerminar(c) }
            }
            val socket = cliente.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(JSONObject().put("tipo", "catalogo").toString())
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                    if (msg.optString("tipo") != "catalogo") return
                    val trilhas = buildList {
                        val arr = msg.optJSONArray("trilhas") ?: org.json.JSONArray()
                        for (i in 0 until arr.length()) {
                            val t = arr.getJSONObject(i)
                            add(TrilhaCatalogo(t.optString("id"), t.optString("nome"), t.optString("descricao")))
                        }
                    }
                    val modelos = buildList {
                        val arr = msg.optJSONArray("modelos") ?: org.json.JSONArray()
                        for (i in 0 until arr.length()) add(arr.getString(i))
                    }
                    terminar(CatalogoPonte(trilhas, modelos, msg.optString("modeloPadrao")))
                    webSocket.close(1000, "catálogo recebido")
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "catálogo indisponível: ${t.message}")
                    terminar(null)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { terminar(null) }
            })
            // Cinto de segurança: ponte muda, não respondeu → devolve null.
            Handler(Looper.getMainLooper()).postDelayed({ terminar(null); runCatching { socket.cancel() } }, 5_000)
        }
    }
}
