package br.com.facilmova.peritavision.net

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
import br.com.facilmova.peritavision.BuildConfig
import br.com.facilmova.peritavision.data.CatalogoPonte
import br.com.facilmova.peritavision.data.TrilhaCatalogo

class PonteGemini(
    private val url: String,
    private val sessaoId: String,
    /** Modelo Gemini Live escolhido em Configurações; vazio = padrão da ponte. */
    private val modelo: String = "",
    /** Trilha fixada em Configurações (id do catálogo); null = a ponte
     *  escolhe pelos materiais do caso (ou abre sem roteiro). */
    private val trilha: String? = null,
    /** Palavras de modo escolhidas pelo perito (modo → palavra); vazio = padrão da ponte. */
    private val palavras: Map<String, String> = emptyMap(),
) {
    var onTranscricao: (String) -> Unit = {}
    /** A ponte abriu a sessão de TRIAGEM: a IA vai perguntar a trilha. */
    var onTriagem: () -> Unit = {}
    /** Trilha definida (id, nome, origem 'app'|'memoria'|'caso'|'padrao'): a sessão
     *  de trabalho está de pé com o roteiro certo. */
    var onTrilha: (id: String, nome: String, origem: String) -> Unit = { _, _, _ -> }
    /** MODO de fala da IA: "conversa" | "silencio" | "pausa" (+ origem
     *  'voz' | 'toque' | 'abertura'). Substitui o chamado por nome. */
    var onModo: (modo: String, origem: String) -> Unit = { _, _ -> }
    /** Achado registrado pela IA (registrar_achado): dado estruturado para o laudo. */
    var onAchado: (JSONObject) -> Unit = {}
    /** Modo atual (o cartão mostra; em pausa o PCM continua indo — é a ponte quem descarta). */
    @Volatile var modo: String = "conversa"
        private set
    var onVoz: (String) -> Unit = {}
    var onResposta: (String) -> Unit = {}
    var onStatus: (String) -> Unit = {}
    /** Chamado quando o servidor confirma que o vídeo dos óculos chegou ao
     *  Gemini — só então o assistente realmente ENXERGA a bancada. */
    var onVideoAtivo: () -> Unit = {}
    /** Janela de visão abriu/fechou: a IA só OLHA quando o perito pede. */
    var onVisao: (Boolean) -> Unit = {}
    var onComando: (id: String, nome: String, argumentos: JSONObject) -> Unit = { _, _, _ -> }

    @Volatile private var pronto = false
    /** true depois de encerrar(): a reconexão automática para de tentar. */
    @Volatile private var encerrado = false
    /** Até quando a voz do assistente está tocando no alto-falante (ms epoch). */
    @Volatile private var falandoAteMs = 0L
    // A janela é esticada pela THREAD DE VOZ, a cada trecho que ela escreve — não pela chegada.
    /** Guardados até o "pronto" (e reenviados após reconexão automática). */
    @Volatile private var urlVideo: String? = null
    @Volatile private var contextoCaso: String? = null
    @Volatile private var ws: WebSocket? = null
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
            .setBufferSizeInBytes(maxOf(minimo * 4, 24_000 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }

    /** Fim previsto do som já escrito no alto-falante (só a thread de voz mexe). */
    @Volatile private var fimDoSomMs = 0L
    private var trechosDaFala = 0

    /** FILA + THREAD PRÓPRIA para a voz. */
    private val filaVoz = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    /** Som na fila, em bytes (PCM16 24 kHz): o teto da fila é em segundos. */
    private val bytesNaFila = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var threadVoz: Thread? = null
    private fun garantirThreadVoz() {
        if (threadVoz?.isAlive == true) return
        threadVoz = Thread({
            while (!encerrado) {
                val pcm = filaVoz.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                bytesNaFila.addAndGet(-pcm.size.toLong())
                val agora = System.currentTimeMillis()
                // FALA NOVA = o som anterior já terminou de sair há mais de 1,2 s.
                val novaFala = agora - fimDoSomMs > 1_200L
                if (novaFala) {
                    var esperou = 0
                    while (filaVoz.size < 2 && esperou < 300 && !encerrado) { Thread.sleep(50); esperou += 50 }
                }
                // Estica a janela pela duração DESTE trecho, aqui, na hora de
                // tocar: é o único ponto em que "está falando" é verdade.
                val duracaoMs = (pcm.size / 2L) * 1000L / 24_000L
                falandoAteMs = maxOf(falandoAteMs, System.currentTimeMillis()) + duracaoMs
                fimDoSomMs = falandoAteMs
                runCatching { tocar(pcm, novaFala) }.onFailure {
                    Log.w(TAG, "voz: falha ao tocar", it)
                    falandoAteMs = 0 // não segurar o microfone por áudio que não saiu
                }
            }
        }, "pv-voz").apply { isDaemon = true; start() }
    }

    private fun descreverRota(t: AudioTrack): String = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            val d = t.routedDevice
            if (d == null) "rota indefinida" else "${d.productName}".ifBlank { "dispositivo ${d.type}" }
        } else "rota n/d"
    }.getOrDefault("rota ?")

    /** Escreve no AudioTrack mantendo a saída VIVA entre uma fala e outra. */
    private fun tocar(pcm: ByteArray, novaFala: Boolean) {
        if (novaFala) {
            // Seguro: o intervalo garante que a fala anterior já terminou de sair, então nada é cortado ao trocar de track.
            track?.let { antigo -> runCatching { antigo.stop(); antigo.release() } }
            track = null
            trechosDaFala = 0
        }

        var t = track ?: runCatching { criarTrack() }.getOrNull()?.also { track = it }
        if (t == null) {
            Log.e(TAG, "voz: não consegui criar o AudioTrack")
            onVoz("ERRO: não consegui abrir a saída de áudio")
            return
        }
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) {
            Log.w(TAG, "AudioTrack não estava tocando (estado ${t.playState}) — religando")
            runCatching { t.play() }
        }
        var r = runCatching { t.write(pcm, 0, pcm.size) }.getOrDefault(AudioTrack.ERROR_DEAD_OBJECT)
        if (r < 0) {
            Log.w(TAG, "AudioTrack morto (código $r) — recriando a saída de voz")
            runCatching { t.release() }
            t = runCatching { criarTrack() }.getOrNull()?.also { track = it }
            if (t == null) { onVoz("ERRO: saída de áudio morreu (código $r)"); return }
            r = runCatching { t.write(pcm, 0, pcm.size) }.getOrDefault(AudioTrack.ERROR_DEAD_OBJECT)
            onVoz(if (r < 0) "ERRO: áudio recusado (código $r)" else "recuperado → ${descreverRota(t)}")
        }
        trechosDaFala += 1
        // Diagnóstico no cartão: no 1º trecho de cada fala (rota) e a cada 20.
        if (trechosDaFala == 1 || trechosDaFala % 20 == 0) {
            val rota = descreverRota(t)
            Log.i(TAG, "voz: fala nova → $rota (trecho $trechosDaFala, estado ${t.playState})")
            onVoz("→ $rota · ${trechosDaFala} trecho(s)")
        }
    }

    fun conectar() {
        encerrado = false
        val pedido = Request.Builder().url(url).build()
        ws = cliente.newWebSocket(pedido, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val iniciar = JSONObject().put("tipo", "iniciar").put("sessaoId", sessaoId)
                // nunca logar o token
                if (BuildConfig.PV_PONTE_TOKEN.isNotEmpty()) iniciar.put("token", BuildConfig.PV_PONTE_TOKEN)
                if (modelo.isNotBlank()) iniciar.put("modelo", modelo)
                if (!trilha.isNullOrBlank()) iniciar.put("trilha", trilha)
                if (palavras.isNotEmpty()) iniciar.put("palavras", JSONObject(palavras))
                webSocket.send(iniciar.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== ws) return // socket antigo
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                runCatching { tratarMensagem(msg) }
                    .onFailure { Log.w(TAG, "falha ao tratar ${msg.optString("tipo")}", it) }
            }

            private fun tratarMensagem(msg: JSONObject) {
                when (msg.optString("tipo")) {
                    "pronto" -> {
                        pronto = true
                        val etapa = msg.optString("etapa")
                        onStatus(
                            if (etapa == "triagem") "Assistente IA pronto — vai perguntar o tipo de exame."
                            else "Assistente IA pronto — pode falar.",
                        )
                        contextoCaso?.let { enviarJson("contexto", "texto", it) }
                        urlVideo?.let { enviarJson("video", "url", it) }
                    }
                    "transcricaoEntrada" -> onTranscricao(msg.optString("texto"))
                    "textoResposta" -> onResposta(msg.optString("texto"))
                    "videoAtivo" -> onVideoAtivo()
                    "visao" -> onVisao(msg.optBoolean("ativa"))
                    "triagem" -> onTriagem()
                    "modo" -> {
                        modo = msg.optString("modo", "conversa")
                        if (modo != "conversa") pararFala()
                        onModo(modo, msg.optString("origem"))
                    }
                    "achado" -> msg.optJSONObject("achado")?.let { onAchado(it) }
                    "trilha" -> onTrilha(msg.textoOu("trilha"), msg.textoOu("nome"), msg.textoOu("origem"))
                    "comando" -> onComando(
                        msg.optString("id"), msg.optString("nome"),
                        msg.optJSONObject("argumentos") ?: JSONObject(),
                    )
                    "erro" -> onStatus("Assistente IA: ${msg.optString("mensagem")}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (webSocket !== ws) return // socket antigo
                // 0x03 = voz de resposta.
                if (bytes.size > 1 && bytes[0] == 0x03.toByte()) {
                    val pcm = bytes.substring(1).toByteArray()
                    val agora = System.currentTimeMillis()
                    // Piso curto: fecha o microfone no instante em que o áudio chega, antes de a thread escrever o primeiro trecho.
                    falandoAteMs = maxOf(falandoAteMs, agora + 300L)
                    // Fila entupida = reprodução emperrada.
                    bytesNaFila.addAndGet(pcm.size.toLong())
                    if (bytesNaFila.get() > 24_000L * 2 * 180) {
                        Log.w(TAG, "voz: fila com mais de 3 min de som (${filaVoz.size} trechos) — descartando")
                        filaVoz.clear(); bytesNaFila.set(0); falandoAteMs = 0
                    }
                    filaVoz.offer(pcm)
                    garantirThreadVoz()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== ws) return
                pronto = false
                Log.w(TAG, "ponte caiu: ${t.message}")
                // AUTOMÁTICO: rede de bancada pisca, servidor reinicia — a ponte se reergue sozinha em vez de esperar um toque.
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

    /** ENCERRAMENTO EM CURSO. */
    @Volatile private var encerrando = false
    fun definirEncerrando(ativo: Boolean) {
        encerrando = ativo
        runCatching { ws?.send(JSONObject().put("tipo", "encerrando").put("ativo", ativo).toString()) }
    }

    /** Troca de modo pelo TOQUE (botões do cartão) — reserva; o perito de
     *  luvas troca pela voz. */
    fun definirModo(novo: String) {
        runCatching { ws?.send(JSONObject().put("tipo", "modo").put("modo", novo).toString()) }
    }

    /** true enquanto a voz do assistente ainda está saindo no alto-falante. */
    fun estaFalando(): Boolean = System.currentTimeMillis() < falandoAteMs + 400L

    /** Corta a fala: esvazia a fila, joga fora o que está no buffer do
     *  alto-falante e libera o microfone na hora. */
    fun pararFala() {
        filaVoz.clear()
        bytesNaFila.set(0)
        falandoAteMs = 0
        runCatching { track?.pause(); track?.flush() }
    }

    /** Cópia do PCM16/16kHz dos óculos. Barato: se a ponte não está pronta, ignora.
     *  HALF-DUPLEX: enquanto o assistente fala, o mic não sobe — ele não se ouve. */
    fun enviarPcm(pcm: ByteArray) {
        // Em PAUSA o áudio continua indo: é o Gemini quem transcreve, e sem isso ninguém ouviria a palavra de volta.
        if (!pronto || encerrando || estaFalando()) return
        val quadro = ByteArray(pcm.size + 1)
        quadro[0] = 0x01
        System.arraycopy(pcm, 0, quadro, 1, pcm.size)
        ws?.send(quadro.toByteString())
    }

    fun enviarQuadro(jpeg: ByteArray) {
        if (!pronto || encerrando || jpeg.isEmpty()) return
        val quadro = ByteArray(jpeg.size + 1)
        quadro[0] = 0x02
        System.arraycopy(jpeg, 0, quadro, 1, jpeg.size)
        runCatching { ws?.send(quadro.toByteString()) }
    }

    fun encerrar() {
        encerrado = true
        pronto = false
        principal.removeCallbacksAndMessages(null)
        runCatching { ws?.close(1000, "encerrado pelo app") }
        pararFala()
        runCatching { track?.stop(); track?.release() }
    }

    companion object {
        private const val TAG = "PonteGemini"

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
                    val pedido = JSONObject().put("tipo", "catalogo")
                    if (BuildConfig.PV_PONTE_TOKEN.isNotEmpty()) pedido.put("token", BuildConfig.PV_PONTE_TOKEN)
                    webSocket.send(pedido.toString())
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
                    val pp = msg.optJSONObject("palavrasPadrao")
                    val palavrasPadrao = if (pp == null) br.com.facilmova.peritavision.data.ConfiguracoesApp.PALAVRAS_PADRAO
                        else buildMap { pp.keys().forEach { k -> put(k, pp.optString(k)) } }
                    terminar(CatalogoPonte(trilhas, modelos, msg.optString("modeloPadrao"), palavrasPadrao))
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
