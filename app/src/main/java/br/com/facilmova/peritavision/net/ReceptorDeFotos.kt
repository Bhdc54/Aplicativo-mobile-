package br.com.facilmova.peritavision.net

import android.util.Log
import br.com.facilmova.peritavision.custodia.CustodiaDeFotos
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ReceptorDeFotos(
    /** Quem guarda o arquivo, o estado e a decisão. Este receptor virou o que
     *  ele deveria ter sido desde o começo: só o HTTP. */
    private val custodia: CustodiaDeFotos,
) {

    /** Uma foto que chegou INTEIRA dos óculos. */
    data class Recebida(
        /** requestId da autorização de captura (vem no caminho da URL). */
        val requestId: String,
        val arquivo: File,
        val bytes: Int,
        val mime: String,
    )

    /** O que a tela precisa saber para diagnosticar sem logcat. */
    data class Estado(
        val ligado: Boolean = false,
        val ip: String? = null,
        val porta: Int = 0,
        /** conexões TCP aceitas na porta desde que o receptor subiu */
        val conexoes: Int = 0,
        val ultimoCliente: String? = null,
        /** resultado do último envio, em texto curto para a tela */
        val ultimoResultado: String? = null,
        val recebidas: Int = 0,
    )

    @Volatile var estado = Estado()
        private set

    /** Chamado (em thread de rede) a cada mudança que interessa à tela. */
    var aoMudar: ((Estado) -> Unit)? = null

    private fun mudar(f: (Estado) -> Estado) {
        estado = f(estado)
        aoMudar?.invoke(estado)
    }

    /** Chamado em thread de rede quando um arquivo completo chega. */
    var aoReceber: ((Recebida) -> Unit)? = null
    var aoEstranhar: ((String) -> Unit)? = null

    /** QUEM pode depositar uma foto. */
    var autorizacaoValida: ((requestId: String, token: String?) -> Boolean)? = null

    @Volatile private var servidor: ServerSocket? = null
    @Volatile var ip: String? = null
        private set
    @Volatile var portaEmUso: Int = 0
        private set
    private var laco: Thread? = null
    private var equipe: ExecutorService? = null

    val ligado: Boolean get() = servidor != null

    /** Sobe o servidor no IP informado (o mesmo que o receptor de vídeo achou na Wi-Fi). */
    fun ligar(ip: String, porta: Int = PORTA_PADRAO): String? {
        if (servidor != null) return null
        custodia.pasta.mkdirs()
        var ultimo: String? = null
        for (p in porta until porta + 5) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(p))
                servidor = s
                portaEmUso = s.localPort
                this.ip = ip
                equipe = Executors.newFixedThreadPool(ATENDENTES)
                runCatching { custodia.faxina() }
                laco = Thread({ aceitar(s) }, "PV-Fotos").apply { isDaemon = true; start() }
                mudar { Estado(ligado = true, ip = ip, porta = s.localPort) }
                Log.i(TAG, "receptor de fotos em http://$ip:$portaEmUso/foto/<requestId>")
                return null
            } catch (e: IOException) {
                ultimo = e.message
            }
        }
        return "não consegui abrir a porta $porta no tablet: $ultimo"
    }

    fun desligar() {
        val s = servidor ?: return
        servidor = null
        runCatching { s.close() }
        laco?.interrupt()
        laco = null
        equipe?.shutdownNow()
        equipe = null
        ip = null
        portaEmUso = 0
        mudar { Estado() }
    }

    /** URL que vai para os óculos NO LUGAR do webhook do backend. */
    fun urlPara(requestId: String): String? {
        val endereco = ip ?: return null
        if (servidor == null) return null
        return "http://$endereco:$portaEmUso/foto/$requestId"
    }

    // Servidor HTTP mínimo (só o que os óculos usam: um POST com um arquivo)

    private fun aceitar(s: ServerSocket) {
        while (servidor === s && !s.isClosed) {
            val cliente = try {
                s.accept()
            } catch (e: IOException) {
                if (servidor !== s || s.isClosed) return
                Log.w(TAG, "accept: ${e.message}")
                continue
            }
            mudar { it.copy(conexoes = it.conexoes + 1, ultimoCliente = cliente.inetAddress?.hostAddress) }
            val pool = equipe
            if (pool == null || pool.isShutdown) { runCatching { cliente.close() }; continue }
            try {
                pool.execute { atender(cliente) }
            } catch (e: Exception) {
                Log.w(TAG, "sem atendente livre: ${e.message}")
                runCatching { cliente.close() }
            }
        }
    }

    private fun atender(cliente: Socket) {
        try {
            cliente.soTimeout = TEMPO_LIMITE_MS
            val entrada = BufferedInputStream(cliente.getInputStream())
            val cru = lerCabecalho(entrada)
            if (cru == null) {
                responder(cliente, 400, "cabecalho HTTP invalido")
                return
            }
            val linhas = String(cru, Charsets.ISO_8859_1).split("\r\n").filter { it.isNotEmpty() }
            val pedido = linhas.firstOrNull()?.split(' ') ?: emptyList()
            val metodo = pedido.getOrNull(0)?.uppercase() ?: ""
            val caminho = pedido.getOrNull(1) ?: "/"
            val cabecalhos = linhas.drop(1).mapNotNull { l ->
                val i = l.indexOf(':')
                if (i <= 0) null else l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
            }.toMap()

            if (metodo == "GET" || metodo == "HEAD" || metodo == "OPTIONS") {
                responder(cliente, 200, "ok")
                return
            }
            if (metodo != "POST" && metodo != "PUT") {
                responder(cliente, 405, "use POST")
                return
            }

            val requestId = requestIdDe(caminho)
            val token = (cabecalhos["authorization"] ?: "").removePrefix("Bearer ").trim().ifBlank { null }
            val validador = autorizacaoValida
            if (validador != null && !validador(requestId, token)) {
                Log.w(TAG, "envio recusado: requestId $requestId não é uma captura em aberto")
                responder(cliente, 403, "captura desconhecida")
                return
            }

            if (cabecalhos["expect"]?.contains("100-continue", true) == true) {
                cliente.getOutputStream().apply {
                    write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
            }

            val tipo = cabecalhos["content-type"] ?: ""
            val tamanho = cabecalhos["content-length"]?.toLongOrNull()
            if (tamanho != null && tamanho > LIMITE_BYTES) {
                responder(cliente, 413, "corpo maior que o limite")
                return
            }
            val corpo = when {
                cabecalhos["transfer-encoding"]?.contains("chunked", true) == true -> lerEmPedacos(entrada)
                tamanho != null -> lerExatos(entrada, tamanho.toInt())
                else -> lerTudo(entrada)
            }
            if (corpo == null) {
                Log.w(TAG, "corpo incompleto ou grande demais: $metodo $caminho tipo=$tipo declarado=$tamanho")
                responder(cliente, 400, "corpo incompleto")
                aoEstranhar?.invoke("o envio dos óculos chegou incompleto (${tamanho ?: 0} bytes prometidos) — refaça a foto")
                return
            }
            Log.i(TAG, "envio recebido: $metodo $caminho tipo=$tipo bytes=${corpo.size}")

            val achado = imagemDoCorpo(corpo, tipo)
            if (achado == null) {
                val amostra = String(corpo.copyOfRange(0, minOf(160, corpo.size)), Charsets.ISO_8859_1)
                    .replace(Regex("[^\\x20-\\x7E]"), ".")
                Log.w(TAG, "envio SEM imagem reconhecível: tipo=$tipo bytes=${corpo.size} inicio=$amostra")
                aoEstranhar?.invoke(
                    "os óculos mandaram ${corpo.size} bytes em \"${tipo.take(60)}\" e não achei imagem dentro"
                )
                responder(cliente, 415, "nao achei imagem no corpo")
                return
            }
            val (bytes, mime) = achado
            // Não sobrescreve: a primeira foto selada para esta captura é a que vale.
            if (custodia.jaTemFoto(requestId)) {
                Log.w(TAG, "envio duplicado para $requestId — recusado")
                responder(cliente, 409, "esta captura ja tem foto")
                return
            }
            val arquivo = custodia.gravarFoto(requestId, bytes, mime)
            if (arquivo == null) {
                responder(cliente, 500, "falha ao gravar")
                return
            }
            Log.i(TAG, "foto ${arquivo.name} gravada no tablet: ${bytes.size} bytes ($mime)")
            mudar { it.copy(recebidas = it.recebidas + 1, ultimoResultado = "recebida: ${bytes.size / 1024} kB") }
            responder(cliente, 201, "recebida")
            aoReceber?.invoke(Recebida(requestId, arquivo, bytes.size, mime))
        } catch (e: Exception) {
            Log.w(TAG, "atendimento falhou: ${e.message}")
            runCatching { responder(cliente, 500, "erro interno") }
        } finally {
            runCatching { cliente.close() }
        }
    }

    /** ".../foto/<requestId>" → requestId. Só letras, dígitos, - e _. */
    private fun requestIdDe(caminho: String): String {
        val semQuery = caminho.substringBefore('?').trimEnd('/')
        val ultimo = semQuery.substringAfterLast('/')
        val limpo = ultimo.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        return if (limpo.length in 4..80) limpo else "sem-id"
    }

    private fun responder(cliente: Socket, codigo: Int, mensagem: String) {
        if (codigo !in 200..299) mudar { it.copy(ultimoResultado = "$codigo $mensagem") }
        val corpo = "{\"ok\":${codigo in 200..299},\"mensagem\":\"$mensagem\"}".toByteArray()
        val cabecalho = buildString {
            append("HTTP/1.1 $codigo ${if (codigo in 200..299) "OK" else "Erro"}\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${corpo.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        cliente.getOutputStream().apply {
            write(cabecalho.toByteArray(Charsets.ISO_8859_1))
            write(corpo)
            flush()
        }
    }

    // Leitura do corpo

    /** Bytes até o "\r\n\r\n" que fecha o cabeçalho (inclusive). */
    private fun lerCabecalho(e: InputStream): ByteArray? {
        val buf = ByteArrayOutputStream()
        var casados = 0
        while (buf.size() < 64 * 1024) {
            val b = e.read()
            if (b < 0) return null
            buf.write(b)
            casados = when {
                casados % 2 == 0 && b == '\r'.code -> casados + 1
                casados % 2 == 1 && b == '\n'.code -> casados + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (casados == 4) return buf.toByteArray()
        }
        return null
    }

    /** Lê EXATAMENTE `quantos` bytes. null se vier menos (corpo cortado) — um
     *  JPEG truncado não pode ser selado como evidência. */
    private fun lerExatos(e: InputStream, quantos: Int): ByteArray? {
        if (quantos < 0 || quantos > LIMITE_BYTES) return null
        val dados = ByteArray(quantos)
        var lidos = 0
        while (lidos < quantos) {
            val n = e.read(dados, lidos, quantos - lidos)
            if (n < 0) return null
            lidos += n
        }
        return dados
    }

    private fun lerTudo(e: InputStream): ByteArray? {
        val buf = ByteArrayOutputStream()
        val pedaco = ByteArray(64 * 1024)
        while (true) {
            val n = e.read(pedaco)
            if (n < 0) break
            buf.write(pedaco, 0, n)
            if (buf.size() > LIMITE_BYTES) return null
        }
        return if (buf.size() == 0) null else buf.toByteArray()
    }

    private fun lerEmPedacos(e: InputStream): ByteArray? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val linha = StringBuilder()
            while (true) {
                val b = e.read()
                if (b < 0) return null
                if (b == '\n'.code) break
                if (b != '\r'.code) linha.append(Char(b))
            }
            val texto = linha.toString().substringBefore(';').trim()
            if (texto.isEmpty()) continue // CRLF sobrando entre pedaços
            val tamanho = texto.toIntOrNull(16) ?: return null
            if (tamanho == 0) break
            if (buf.size() + tamanho > LIMITE_BYTES) return null
            val dados = lerExatos(e, tamanho) ?: return null
            buf.write(dados)
            if (e.read() < 0 || e.read() < 0) return null // o CRLF que fecha o pedaço
        }
        return if (buf.size() == 0) null else buf.toByteArray()
    }

    // Onde está a imagem no corpo

    /** Acha o JPEG/PNG dentro do que chegou. */
    private fun imagemDoCorpo(corpo: ByteArray, contentType: String): Pair<ByteArray, String>? {
        val ct = contentType.lowercase()
        if (ct.startsWith("multipart/")) {
            val limite = Regex("boundary=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
                .find(contentType)?.groupValues?.get(1)?.trim()
            if (!limite.isNullOrEmpty()) {
                arquivoNoMultipart(corpo, "--$limite")?.let { return it }
            }
        }
        assinatura(corpo)?.let { return corpo to it }
        if (ct.startsWith("image/")) return corpo to ct.substringBefore(';').trim()
        return null
    }

    private fun arquivoNoMultipart(corpo: ByteArray, limite: String): Pair<ByteArray, String>? {
        val marca = limite.toByteArray(Charsets.ISO_8859_1)
        var candidato: Pair<ByteArray, String>? = null
        var pos = indiceDe(corpo, marca, 0)
        while (pos >= 0) {
            val depois = pos + marca.size
            if (depois + 2 <= corpo.size &&
                corpo[depois] == '-'.code.toByte() && corpo[depois + 1] == '-'.code.toByte()
            ) break // "--limite--": fim do multipart
            val fimCabecalho = indiceDe(corpo, SEPARADOR, depois)
            if (fimCabecalho < 0) break
            val proximo = indiceDe(corpo, marca, fimCabecalho + SEPARADOR.size)
            // O CRLF antes do próximo limite pertence ao protocolo, não ao arquivo.
            val fimConteudo = if (proximo < 0) corpo.size else proximo - 2
            if (fimConteudo <= fimCabecalho + SEPARADOR.size) { pos = proximo; continue }
            val cabecalho = String(corpo, depois, fimCabecalho - depois, Charsets.ISO_8859_1).lowercase()
            val conteudo = corpo.copyOfRange(fimCabecalho + SEPARADOR.size, fimConteudo)
            val mime = Regex("content-type:\\s*([^\\r\\n;]+)").find(cabecalho)?.groupValues?.get(1)?.trim()
            val ehArquivo = cabecalho.contains("filename=") || (mime?.startsWith("image/") == true)
            if (ehArquivo || assinatura(conteudo) != null) {
                val achado = conteudo to (assinatura(conteudo) ?: mime ?: "image/jpeg")
                if (cabecalho.contains("name=\"photo\"")) return achado
                val atual = candidato
                if (atual == null || conteudo.size > atual.first.size) candidato = achado
            }
            pos = proximo
        }
        return candidato
    }

    private fun assinatura(dados: ByteArray): String? {
        if (dados.size < 8) return null
        if (dados[0] == 0xFF.toByte() && dados[1] == 0xD8.toByte()) return "image/jpeg"
        if (dados[0] == 0x89.toByte() && dados[1] == 'P'.code.toByte() &&
            dados[2] == 'N'.code.toByte() && dados[3] == 'G'.code.toByte()
        ) return "image/png"
        return null
    }

    private fun indiceDe(onde: ByteArray, agulha: ByteArray, de: Int): Int {
        if (agulha.isEmpty() || de < 0) return -1
        val limite = onde.size - agulha.size
        var i = de
        while (i <= limite) {
            var j = 0
            while (j < agulha.size && onde[i + j] == agulha[j]) j++
            if (j == agulha.size) return i
            i++
        }
        return -1
    }

    companion object {
        const val TAG = "PV-Fotos"
        /** Fora das portas conhecidas e longe da 1935 do RTMP. */
        const val PORTA_PADRAO = 8099
        /** Uma foto MEDIUM dos óculos tem 1–3 MB; 12 MB é folga com sobra, e
         *  mantém o heap do tablet longe do limite enquanto grava vídeo. */
        private const val LIMITE_BYTES = 12 * 1024 * 1024
        private const val TEMPO_LIMITE_MS = 15_000
        private const val ATENDENTES = 4
        private val SEPARADOR = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
    }
}
