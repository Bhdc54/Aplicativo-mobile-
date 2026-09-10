// Receptor RTMP do PeritaVision — roda NO TABLET.
//
// Os óculos Mentra publicam o vídeo por RTMP para uma URL que o app manda por
// BLE. Até aqui essa URL era a VPS: o vídeo atravessava a internet e o tablet
// puxava de volta para mostrar. Este arquivo faz o tablet ser o destino: os
// óculos publicam em rtmp://<ip-do-tablet>:1935/pv/<sessão> pela Wi-Fi da
// bancada, e o tablet grava o que chega em segmentos .flv — sem recodificar,
// sem depender da internet — e entrega cada quadro de vídeo por callback para
// o preview local e para a IA enxergar.
//
// Sem dependência: é só socket e bytes. O RTMP é um protocolo de 2009 com
// três partes — handshake, chunks e comandos AMF0 — e os pacotes de áudio e
// vídeo já vêm no formato das tags FLV, então gravar é reescrever cabeçalho.
// Por que não uma biblioteca: não existe servidor RTMP pronto para Android; o
// ffmpeg-kit foi aposentado em 2025; e isto vai rodar num sistema da polícia,
// onde cada dependência é um risco a mais para auditar.
//
// Compatível com o handshake SIMPLES (S1 com versão zerada). É o que o cliente
// dos óculos usa, e o que faz o ffmpeg tomar o caminho sem digest — sem isso
// o ffmpeg tenta validar assinatura e falha.
//
// Validado em 05/09/2026 com o ffmpeg no papel dos óculos: 720p30 a 3 Mbps e
// 1080p30 a 6 Mbps sem perder quadro; kill -9 do publicador fecha o segmento
// íntegro; publicar de novo abre outro segmento.
package br.com.facilmova.peritavision.rtmp

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class RtmpIngest(
    private val porta: Int = 1935,
    private val pastaSaida: File,
    private val aoEvento: (Evento) -> Unit,
    /** Sem dados por este tempo = óculos sumiram: fecha o segmento. */
    private val semDadosMs: Int = 20_000,
) : Closeable {

    sealed interface Evento {
        /** Um cliente começou a publicar. `chave` é o último trecho da URL (a sessão). */
        data class Publicando(val app: String, val chave: String, val arquivo: File) : Evento

        /** Um quadro de vídeo chegou (payload da tag FLV: [frameType|codec][avcPacketType][cts x3][dados]). */
        data class Quadro(
            val chave: String,
            val dados: ByteArray,
            val timestampMs: Int,
            val keyframe: Boolean,
            val cabecalhoDeSequencia: Boolean,
        ) : Evento

        /** O segmento fechou (cliente desconectou, deleteStream, ou ficou mudo). */
        data class Encerrado(
            val chave: String,
            val arquivo: File,
            val bytes: Long,
            val quadros: Int,
            val duracaoMs: Int,
            val motivo: String,
        ) : Evento

        /** Alguém abriu TCP na porta, antes de qualquer handshake. No teste de
         *  campo é o que separa "a Wi-Fi não deixa os óculos chegarem ao
         *  tablet" (nada acontece) de "chegaram e o handshake falhou". */
        data class Conectou(val de: String) : Evento

        data class Erro(val mensagem: String) : Evento
    }

    private var servidor: ServerSocket? = null
    private val conexoes = CopyOnWriteArrayList<Conexao>()
    private val encerrado = AtomicBoolean(false)
    private var threadAceite: Thread? = null

    /** Sobe o servidor. Lança IOException se a porta estiver ocupada. */
    fun iniciar() {
        val s = ServerSocket(porta)
        s.reuseAddress = true
        servidor = s
        threadAceite = Thread({
            while (!encerrado.get()) {
                val cliente = try { s.accept() } catch (e: IOException) { if (!encerrado.get()) aoEvento(Evento.Erro("accept: ${e.message}")); break }
                aoEvento(Evento.Conectou(cliente.inetAddress.hostAddress ?: "?"))
                val c = Conexao(cliente)
                conexoes += c
                Thread({ try { c.atender() } finally { conexoes -= c } }, "rtmp-cliente-${cliente.inetAddress.hostAddress}").start()
            }
        }, "rtmp-aceite-$porta").apply { isDaemon = true; start() }
    }

    val portaEmUso: Int get() = servidor?.localPort ?: porta

    fun encerrar() {
        if (!encerrado.compareAndSet(false, true)) return
        try { servidor?.close() } catch (_: IOException) {}
        conexoes.forEach { it.fechar("servidor encerrado") }
    }

    override fun close() = encerrar()

    // ────────────────────────────────────────────────────────────────────────
    // Uma conexão = um publicador (os óculos). Estado do parser de chunks e a
    // gravação do segmento vivem aqui.
    private inner class Conexao(private val socket: Socket) {
        private val entrada: DataInputStream
        private val saida: OutputStream
        private var fechada = false

        // Estado de recepção de chunks
        private var tamanhoChunkEntrada = 128
        private val fluxos = HashMap<Int, EstadoFluxo>()
        private var bytesRecebidos = 0L
        private var ultimoAck = 0L
        private var janelaAck = 2_500_000L

        // Estado de envio
        private val tamanhoChunkSaida = 128

        // Publicação
        private var app = ""
        private var chave = ""
        private var gravador: GravadorFlv? = null
        private var quadros = 0
        private var ultimoTimestamp = 0

        init {
            socket.soTimeout = semDadosMs
            socket.tcpNoDelay = true
            entrada = DataInputStream(BufferedInputStream(socket.getInputStream(), 256 * 1024))
            saida = BufferedOutputStream(socket.getOutputStream(), 16 * 1024)
        }

        fun atender() {
            try {
                handshake()
                while (!fechada) lerChunk()
            } catch (e: SocketTimeoutException) {
                fechar("sem dados por ${semDadosMs / 1000} s")
            } catch (e: EOFException) {
                fechar("cliente desconectou")
            } catch (e: SocketException) {
                fechar(if (fechada) "encerrado" else "socket: ${e.message}")
            } catch (e: Exception) {
                aoEvento(Evento.Erro("conexão ${socket.inetAddress.hostAddress}: ${e.message}"))
                fechar("erro: ${e.message}")
            }
        }

        fun fechar(motivo: String) {
            if (fechada) return
            fechada = true
            gravador?.let { g ->
                val r = g.fechar()
                aoEvento(Evento.Encerrado(chave, g.arquivo, r.first, quadros, ultimoTimestamp, motivo))
            }
            gravador = null
            try { socket.close() } catch (_: IOException) {}
        }

        // ── Handshake simples ─────────────────────────────────────────────
        private fun handshake() {
            val c0 = entrada.readUnsignedByte()
            if (c0 != 3) throw IOException("versão RTMP inesperada: $c0")
            val c1 = ByteArray(1536); entrada.readFully(c1)
            val s1 = ByteArray(1536).also { Random.nextBytes(it) }
            // time = 0, versão = 0 (bytes 4..7 zerados → cliente usa o caminho sem digest)
            for (i in 0 until 8) s1[i] = 0
            saida.write(3)
            saida.write(s1)
            // S2 = eco de C1 (time do cliente, nosso time, random do cliente)
            val s2 = c1.copyOf()
            saida.write(s2)
            saida.flush()
            val c2 = ByteArray(1536); entrada.readFully(c2)
        }

        // ── Chunks ────────────────────────────────────────────────────────
        private inner class EstadoFluxo {
            var timestamp = 0
            var delta = 0
            var tamanho = 0
            var tipo = 0
            var streamId = 0
            var estendido = false
            var corpo: ByteArrayOutputStream? = null
        }

        private fun lerChunk() {
            val b0 = entrada.readUnsignedByte(); conta(1)
            val fmt = b0 ushr 6
            var csid = b0 and 0x3f
            if (csid == 0) { csid = 64 + entrada.readUnsignedByte(); conta(1) }
            else if (csid == 1) { val a = entrada.readUnsignedByte(); val b = entrada.readUnsignedByte(); csid = 64 + a + b * 256; conta(2) }
            val f = fluxos.getOrPut(csid) { EstadoFluxo() }

            when (fmt) {
                0 -> {
                    val ts = lerU24(); f.tamanho = lerU24(); f.tipo = entrada.readUnsignedByte(); f.streamId = lerU32LE(); conta(11)
                    f.estendido = ts == 0xFFFFFF
                    f.timestamp = if (f.estendido) { conta(4); entrada.readInt() } else ts
                    f.delta = 0
                }
                1 -> {
                    val d = lerU24(); f.tamanho = lerU24(); f.tipo = entrada.readUnsignedByte(); conta(7)
                    f.estendido = d == 0xFFFFFF
                    f.delta = if (f.estendido) { conta(4); entrada.readInt() } else d
                    if (f.corpo == null) f.timestamp += f.delta
                }
                2 -> {
                    val d = lerU24(); conta(3)
                    f.estendido = d == 0xFFFFFF
                    f.delta = if (f.estendido) { conta(4); entrada.readInt() } else d
                    if (f.corpo == null) f.timestamp += f.delta
                }
                3 -> {
                    // Continuação. Se o chunk anterior deste fluxo tinha timestamp
                    // estendido, ele vem repetido aqui (regra da especificação que
                    // quase todo cliente segue).
                    if (f.estendido) { conta(4); entrada.readInt() }
                    if (f.corpo == null) f.timestamp += f.delta
                }
            }

            val corpo = f.corpo ?: ByteArrayOutputStream(f.tamanho.coerceAtLeast(16)).also { f.corpo = it }
            val falta = f.tamanho - corpo.size()
            val agora = minOf(falta, tamanhoChunkEntrada)
            if (agora > 0) {
                val buf = ByteArray(agora); entrada.readFully(buf); conta(agora)
                corpo.write(buf)
            }
            if (corpo.size() >= f.tamanho) {
                f.corpo = null
                tratarMensagem(csid, f.tipo, f.streamId, f.timestamp, corpo.toByteArray())
            }
            reconhecerSePrecisar()
        }

        private fun conta(n: Int) { bytesRecebidos += n }

        /** Acknowledgement (tipo 3): sem ele alguns clientes travam ao encher a janela. */
        private fun reconhecerSePrecisar() {
            if (bytesRecebidos - ultimoAck >= janelaAck) {
                ultimoAck = bytesRecebidos
                enviarMensagem(2, 3, 0, 0, u32(bytesRecebidos.toInt()))
            }
        }

        private fun tratarMensagem(csid: Int, tipo: Int, streamId: Int, timestamp: Int, corpo: ByteArray) {
            when (tipo) {
                1 -> tamanhoChunkEntrada = lerU32BE(corpo, 0).coerceIn(1, 0xFFFFFF)   // Set Chunk Size
                2 -> fluxos[lerU32BE(corpo, 0)]?.corpo = null                          // Abort
                3 -> {}                                                                // Acknowledgement do cliente
                4 -> tratarControleDeUsuario(corpo)
                5 -> janelaAck = lerU32BE(corpo, 0).toLong().coerceAtLeast(1)          // Window Ack Size
                6 -> {}                                                                // Set Peer Bandwidth
                8 -> gravar(8, timestamp, corpo)                                       // áudio
                9 -> { gravar(9, timestamp, corpo); emitirQuadro(timestamp, corpo) }   // vídeo
                18 -> tratarDados(timestamp, corpo)                                    // AMF0 data (@setDataFrame)
                20 -> tratarComando(csid, streamId, corpo)                             // AMF0 command
                15, 17 -> {}                                                           // AMF3: os óculos não usam
                else -> {}
            }
        }

        private fun tratarControleDeUsuario(corpo: ByteArray) {
            if (corpo.size < 6) return
            val evento = ((corpo[0].toInt() and 0xff) shl 8) or (corpo[1].toInt() and 0xff)
            if (evento == 6) { // PingRequest → PingResponse
                enviarMensagem(2, 4, 0, 0, byteArrayOf(0, 7) + corpo.copyOfRange(2, 6))
            }
        }

        // ── Comandos AMF0 ─────────────────────────────────────────────────
        private fun tratarComando(csid: Int, streamId: Int, corpo: ByteArray) {
            val valores = Amf0.decodificarTodos(corpo)
            val nome = valores.getOrNull(0) as? String ?: return
            val transacao = (valores.getOrNull(1) as? Double) ?: 0.0
            when (nome) {
                "connect" -> {
                    val obj = valores.getOrNull(2) as? Map<*, *>
                    app = (obj?.get("app") as? String ?: "").trim('/')
                    enviarMensagem(2, 5, 0, 0, u32(2_500_000))                     // Window Ack Size
                    enviarMensagem(2, 6, 0, 0, u32(2_500_000) + byteArrayOf(2))     // Set Peer BW (dinâmico)
                    enviarMensagem(2, 4, 0, 0, byteArrayOf(0, 0) + u32(0))          // Stream Begin 0
                    enviarComando(3, 0, "_result", transacao,
                        mapOf("fmsVer" to "FMS/3,5,7,7009", "capabilities" to 31.0, "mode" to 1.0),
                        mapOf("level" to "status", "code" to "NetConnection.Connect.Success",
                              "description" to "Connection succeeded.", "objectEncoding" to 0.0))
                }
                "createStream" -> enviarComando(3, 0, "_result", transacao, null, 1.0)
                "releaseStream", "FCPublish", "getStreamLength", "FCSubscribe" ->
                    if (transacao != 0.0) enviarComando(3, 0, "_result", transacao, null, null)
                "publish" -> {
                    val nomeStream = (valores.getOrNull(3) as? String ?: "").substringBefore('?')
                    chave = nomeStream.ifBlank { "sem-chave" }
                    enviarMensagem(2, 4, 0, 0, byteArrayOf(0, 0) + u32(1))          // Stream Begin 1
                    enviarComando(5, 1, "onStatus", 0.0, null,
                        mapOf("level" to "status", "code" to "NetStream.Publish.Start",
                              "description" to "$chave is now published.", "details" to chave))
                    abrirSegmento()
                }
                "FCUnpublish", "deleteStream", "closeStream" -> {
                    if (transacao != 0.0) enviarComando(3, 0, "_result", transacao, null, null)
                    fechar("cliente encerrou a publicação ($nome)")
                }
                else -> if (transacao != 0.0) enviarComando(3, 0, "_result", transacao, null, null)
            }
        }

        private fun tratarDados(timestamp: Int, corpo: ByteArray) {
            // "@setDataFrame" "onMetaData" {…} → grava só a partir de "onMetaData",
            // que é o que um arquivo FLV normal carrega.
            val valores = Amf0.decodificarTodos(corpo)
            if ((valores.getOrNull(0) as? String) == "@setDataFrame") {
                val primeiro = Amf0.tamanhoDoPrimeiro(corpo)
                gravar(18, timestamp, corpo.copyOfRange(primeiro, corpo.size))
            } else gravar(18, timestamp, corpo)
        }

        // ── Gravação ──────────────────────────────────────────────────────
        private fun abrirSegmento() {
            gravador?.fechar()
            val pasta = File(pastaSaida, chave).apply { mkdirs() }
            val arquivo = File(pasta, "${System.currentTimeMillis()}.flv")
            gravador = GravadorFlv(arquivo)
            quadros = 0; ultimoTimestamp = 0
            aoEvento(Evento.Publicando(app, chave, arquivo))
        }

        private fun gravar(tipoTag: Int, timestamp: Int, dados: ByteArray) {
            val g = gravador ?: return
            g.tag(tipoTag, timestamp, dados)
            if (timestamp > ultimoTimestamp) ultimoTimestamp = timestamp
        }

        private fun emitirQuadro(timestamp: Int, dados: ByteArray) {
            if (dados.size < 2) return
            val frameType = (dados[0].toInt() and 0xf0) ushr 4
            val codec = dados[0].toInt() and 0x0f
            val sequencia = codec == 7 && dados[1].toInt() == 0
            if (!sequencia) quadros += 1
            aoEvento(Evento.Quadro(chave, dados, timestamp, frameType == 1, sequencia))
            // Keyframe = ponto seguro para forçar o disco: perde-se no máximo um GOP se o app cair.
            if (frameType == 1) gravador?.sincronizar()
        }

        // ── Envio ─────────────────────────────────────────────────────────
        private fun enviarComando(csid: Int, streamId: Int, nome: String, transacao: Double, vararg args: Any?) {
            val b = ByteArrayOutputStream()
            Amf0.codificar(b, nome); Amf0.codificar(b, transacao)
            args.forEach { Amf0.codificar(b, it) }
            enviarMensagem(csid, 20, streamId, 0, b.toByteArray())
        }

        @Synchronized
        private fun enviarMensagem(csid: Int, tipo: Int, streamId: Int, timestamp: Int, corpo: ByteArray) {
            if (fechada) return
            try {
                var pos = 0
                var primeiro = true
                while (pos < corpo.size || primeiro) {
                    val n = minOf(tamanhoChunkSaida, corpo.size - pos)
                    if (primeiro) {
                        saida.write(csid and 0x3f) // fmt 0
                        saida.write(u24(timestamp)); saida.write(u24(corpo.size)); saida.write(tipo)
                        saida.write(byteArrayOf(streamId.toByte(), (streamId ushr 8).toByte(), (streamId ushr 16).toByte(), (streamId ushr 24).toByte()))
                        primeiro = false
                    } else saida.write(0xc0 or (csid and 0x3f)) // fmt 3
                    saida.write(corpo, pos, n); pos += n
                }
                saida.flush()
            } catch (e: IOException) { fechar("falha ao enviar: ${e.message}") }
        }

        private fun lerU24(): Int = (entrada.readUnsignedByte() shl 16) or (entrada.readUnsignedByte() shl 8) or entrada.readUnsignedByte()
        private fun lerU32LE(): Int { val a = entrada.readUnsignedByte(); val b = entrada.readUnsignedByte(); val c = entrada.readUnsignedByte(); val d = entrada.readUnsignedByte(); return a or (b shl 8) or (c shl 16) or (d shl 24) }
    }

    // ────────────────────────────────────────────────────────────────────────
    /** Escreve um .flv: cabeçalho + tags com o payload exatamente como veio no RTMP. */
    private class GravadorFlv(val arquivo: File) {
        private val fos = FileOutputStream(arquivo)
        private val saida = BufferedOutputStream(fos, 256 * 1024)
        private var bytes = 0L
        private var fechado = false

        init {
            // "FLV", versão 1, flags = áudio+vídeo (5), tamanho do cabeçalho 9, PreviousTagSize0
            val cab = byteArrayOf('F'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte(), 1, 5, 0, 0, 0, 9, 0, 0, 0, 0)
            saida.write(cab); bytes += cab.size
        }

        fun tag(tipo: Int, timestamp: Int, dados: ByteArray) {
            if (fechado) return
            saida.write(tipo)
            saida.write(u24(dados.size))
            saida.write(u24(timestamp and 0xFFFFFF)); saida.write((timestamp ushr 24) and 0xff)
            saida.write(byteArrayOf(0, 0, 0))
            saida.write(dados)
            saida.write(u32(11 + dados.size))
            bytes += 11L + dados.size + 4
        }

        fun sincronizar() { try { saida.flush(); fos.fd.sync() } catch (_: IOException) {} }

        /** Devolve (bytes gravados, ok). */
        fun fechar(): Pair<Long, Boolean> {
            if (fechado) return bytes to true
            fechado = true
            return try { saida.flush(); fos.fd.sync(); fos.close(); bytes to true } catch (e: IOException) { bytes to false }
        }
    }

    companion object {
        fun u24(v: Int) = byteArrayOf((v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        fun lerU32BE(b: ByteArray, i: Int): Int =
            ((b[i].toInt() and 0xff) shl 24) or ((b[i + 1].toInt() and 0xff) shl 16) or ((b[i + 2].toInt() and 0xff) shl 8) or (b[i + 3].toInt() and 0xff)
    }
}

// ────────────────────────────────────────────────────────────────────────────
/** AMF0 — só os tipos que connect/createStream/publish/onMetaData usam. */
object Amf0 {
    fun codificar(s: OutputStream, v: Any?) {
        when (v) {
            null -> s.write(0x05)
            is Double -> { s.write(0x00); val l = java.lang.Double.doubleToLongBits(v); for (i in 7 downTo 0) s.write(((l ushr (i * 8)) and 0xff).toInt()) }
            is Int -> codificar(s, v.toDouble())
            is Boolean -> { s.write(0x01); s.write(if (v) 1 else 0) }
            is String -> { val b = v.toByteArray(StandardCharsets.UTF_8); s.write(0x02); s.write(b.size ushr 8); s.write(b.size and 0xff); s.write(b) }
            is Map<*, *> -> {
                s.write(0x03)
                for ((k, valor) in v) { val b = k.toString().toByteArray(StandardCharsets.UTF_8); s.write(b.size ushr 8); s.write(b.size and 0xff); s.write(b); codificar(s, valor) }
                s.write(0); s.write(0); s.write(0x09)
            }
            else -> codificar(s, v.toString())
        }
    }

    /** Decodifica todos os valores concatenados num corpo de mensagem. */
    fun decodificarTodos(b: ByteArray): List<Any?> {
        val saida = ArrayList<Any?>()
        val pos = intArrayOf(0)
        while (pos[0] < b.size) {
            val antes = pos[0]
            saida += try { decodificar(b, pos) } catch (e: Exception) { break }
            if (pos[0] <= antes) break
        }
        return saida
    }

    /** Quantos bytes o primeiro valor ocupa (para pular "@setDataFrame"). */
    fun tamanhoDoPrimeiro(b: ByteArray): Int { val pos = intArrayOf(0); decodificar(b, pos); return pos[0] }

    private fun decodificar(b: ByteArray, pos: IntArray): Any? {
        val marcador = b[pos[0]].toInt() and 0xff; pos[0]++
        return when (marcador) {
            0x00 -> { var l = 0L; for (i in 0 until 8) l = (l shl 8) or (b[pos[0] + i].toLong() and 0xff); pos[0] += 8; java.lang.Double.longBitsToDouble(l) }
            0x01 -> { val v = b[pos[0]].toInt() != 0; pos[0]++; v }
            0x02 -> lerString(b, pos)
            0x03 -> lerObjeto(b, pos)
            0x05, 0x06 -> null
            0x08 -> { pos[0] += 4; lerObjeto(b, pos) } // ECMA array: contagem + pares + fim
            0x0A -> { val n = RtmpIngest.lerU32BE(b, pos[0]); pos[0] += 4; List(n) { decodificar(b, pos) } }
            0x0B -> { pos[0] += 10; null } // date
            0x0C -> { val n = RtmpIngest.lerU32BE(b, pos[0]); pos[0] += 4; val s = String(b, pos[0], n, StandardCharsets.UTF_8); pos[0] += n; s }
            else -> throw IOException("AMF0 marcador não suportado: $marcador")
        }
    }

    private fun lerString(b: ByteArray, pos: IntArray): String {
        val n = ((b[pos[0]].toInt() and 0xff) shl 8) or (b[pos[0] + 1].toInt() and 0xff); pos[0] += 2
        val s = String(b, pos[0], n, StandardCharsets.UTF_8); pos[0] += n
        return s
    }

    private fun lerObjeto(b: ByteArray, pos: IntArray): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        while (pos[0] < b.size) {
            if ((b[pos[0]].toInt() and 0xff) == 0 && (b[pos[0] + 1].toInt() and 0xff) == 0 && (b[pos[0] + 2].toInt() and 0xff) == 0x09) { pos[0] += 3; break }
            val nome = lerString(b, pos)
            m[nome] = decodificar(b, pos)
        }
        return m
    }
}
