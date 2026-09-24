package br.com.facilmova.peritavision.custodia

import java.io.File
import java.util.concurrent.ConcurrentHashMap

class CustodiaDeFotos(val pasta: File) {

    /** Autorização de captura emitida pelo servidor: token de USO ÚNICO. */
    data class Credencial(
        val sessaoId: String,
        val requestId: String,
        /** endereço público do webhook do backend — o destino do repasse */
        val webhookUrl: String,
        val authToken: String,
    )

    /** O que a bancada mostra. Imutável: a tela recebe uma cópia. */
    data class Resumo(
        /** fotos que o SERVIDOR aceitou (é o único número que vale) */
        val noServidor: Int = 0,
        /** repasses em voo neste instante */
        val aguardandoRede: Int = 0,
        /** arquivos completos parados na pasta, esperando o servidor */
        val paradasNoTablet: Int = 0,
        /** capturas contadas como marca no vídeo por não ter chegado foto */
        val marcadasNoVideo: Int = 0,
        /** false = os óculos não chegaram nem à porta; o caminho volta a ser
         *  o webhook público */
        val usandoTablet: Boolean = true,
    )

    @Volatile var resumo = Resumo()
        private set

    /** Chamado a cada mudança de resumo, em qualquer thread. */
    var aoMudar: ((Resumo) -> Unit)? = null

    private val credenciais = ConcurrentHashMap<String, Credencial>()
    private val marcadas = ConcurrentHashMap.newKeySet<String>()
    private val emEnvio = ConcurrentHashMap.newKeySet<String>()

    private fun mudar(f: (Resumo) -> Resumo) {
        val novo = f(resumo)
        resumo = novo
        aoMudar?.invoke(novo)
    }

    // Credenciais: memória E disco

    /** Guarda a credencial na memória e AO LADO do JPEG que vai chegar. */
    fun guardar(c: Credencial) {
        credenciais[c.requestId] = c
        runCatching {
            pasta.mkdirs()
            arquivoDeCredencial(c.requestId).writeText(
                "sessaoId=${umaLinha(c.sessaoId)}\n" +
                    "requestId=${umaLinha(c.requestId)}\n" +
                    "webhookUrl=${umaLinha(c.webhookUrl)}\n" +
                    "authToken=${umaLinha(c.authToken)}\n",
                Charsets.UTF_8,
            )
        }
    }

    /** A credencial de uma captura: da memória, senão do arquivo ao lado do JPEG. */
    fun credencial(requestId: String, daSessao: String?): Credencial? {
        val naMemoria = credenciais[requestId]
        if (naMemoria != null) {
            return if (daSessao == null || naMemoria.sessaoId == daSessao) naMemoria else null
        }
        val arquivo = arquivoDeCredencial(requestId)
        if (!arquivo.exists()) return null
        val campos = runCatching {
            arquivo.readLines(Charsets.UTF_8).mapNotNull { l ->
                val i = l.indexOf('=')
                if (i <= 0) null else l.substring(0, i) to l.substring(i + 1)
            }.toMap()
        }.getOrNull() ?: return null
        val c = Credencial(
            sessaoId = campos["sessaoId"] ?: return null,
            requestId = campos["requestId"] ?: requestId,
            webhookUrl = campos["webhookUrl"] ?: return null,
            authToken = campos["authToken"] ?: "",
        )
        if (daSessao != null && c.sessaoId != daSessao) return null
        return c
    }

    fun arquivoDeCredencial(requestId: String): File = File(pasta, "${nomeSeguro(requestId)}.cred")

    // requestId vira nome de arquivo: só caracteres seguros (sem "/" nem "..").
    private fun nomeSeguro(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.')

    private fun umaLinha(v: String) = v.replace('\n', ' ').replace('\r', ' ')

    // Arquivos da foto

    /** Já existe foto (pendente ou já enviada) para esta captura? */
    fun jaTemFoto(requestId: String): Boolean =
        EXTENSOES.any { e ->
            File(pasta, "${nomeSeguro(requestId)}.$e").exists() || File(pasta, "${nomeSeguro(requestId)}.$e$SUFIXO_ENVIADA").exists()
        }

    fun gravarFoto(requestId: String, bytes: ByteArray, mime: String): File? {
        val extensao = if (mime.contains("png", true)) "png" else "jpg"
        val destino = File(pasta, "${nomeSeguro(requestId)}.$extensao")
        if (jaTemFoto(requestId)) return null
        return runCatching {
            pasta.mkdirs()
            val temporario = File(pasta, "${nomeSeguro(requestId)}.parcial")
            temporario.outputStream().use { it.write(bytes) }
            if (!temporario.renameTo(destino)) {
                temporario.delete()
                null
            } else {
                recontarParadas()
                destino
            }
        }.getOrNull()
    }

    /** Fotos COMPLETAS ainda não aceitas pelo servidor. */
    fun pendentes(): List<File> =
        pasta.listFiles { f ->
            f.isFile && EXTENSOES.any { f.name.endsWith(".$it") }
        }?.sortedBy { it.name } ?: emptyList()

    /** Pendentes que pertencem a esta sessão, com o requestId de cada uma. */
    fun pendentesDaSessao(sessaoId: String): List<Pair<String, File>> =
        pendentes().mapNotNull { arquivo ->
            val id = requestIdDoArquivo(arquivo)
            if (credencial(id, sessaoId) == null) null else id to arquivo
        }

    fun requestIdDoArquivo(arquivo: File): String =
        arquivo.name.removeSuffix(SUFIXO_ENVIADA).substringBeforeLast('.')

    /** Apaga o que já subiu há mais de [dias] e `.parcial` abandonado. */
    fun faxina(dias: Int = 7) {
        val limiteEnviadas = System.currentTimeMillis() - dias * 24L * 3_600_000L
        val limiteParciais = System.currentTimeMillis() - 3_600_000L
        pasta.listFiles()?.forEach { f ->
            if (f.name.endsWith(SUFIXO_ENVIADA) && f.lastModified() < limiteEnviadas) f.delete()
            if (f.name.endsWith(".parcial") && f.lastModified() < limiteParciais) f.delete()
        }
        recontarParadas()
    }

    // Repasse: uma captura de cada vez

    /** Reserva a captura para o repasse. false = já tem um envio em voo. */
    fun tomarParaEnvio(requestId: String): Boolean {
        val tomou = emEnvio.add(requestId)
        if (tomou) mudar { it.copy(aguardandoRede = emEnvio.size) }
        return tomou
    }

    /** Solta a reserva sem sucesso: o arquivo continua pendente. */
    fun devolver(requestId: String) {
        emEnvio.remove(requestId)
        mudar { it.copy(aguardandoRede = emEnvio.size, paradasNoTablet = pendentes().size) }
    }

    /** O servidor ACEITOU a foto. */
    fun confirmarEnvio(requestId: String, arquivo: File): Boolean {
        emEnvio.remove(requestId)
        credenciais.remove(requestId)
        val marcaDesfeita = marcadas.remove(requestId)
        val renomeou = runCatching {
            arquivo.renameTo(File(arquivo.parentFile, arquivo.name + SUFIXO_ENVIADA))
        }.getOrDefault(false)
        runCatching { arquivoDeCredencial(requestId).delete() }
        mudar {
            it.copy(
                noServidor = it.noServidor + 1,
                aguardandoRede = emEnvio.size,
                paradasNoTablet = pendentes().size,
                marcadasNoVideo = marcadas.size,
                usandoTablet = true, // chegou foto: o caminho do tablet funciona
            )
        }
        if (marcaDesfeita) { /* a marca no vídeo caiu: chegou foto de verdade */ }
        return renomeou
    }

    // A decisão: tablet ou webhook público

    /** A foto não chegou no prazo. */
    fun desistirDaFoto(requestId: String, houveConexaoNaPorta: Boolean): Boolean {
        val marcouAgora = marcadas.add(requestId)
        mudar {
            it.copy(
                marcadasNoVideo = marcadas.size,
                usandoTablet = if (houveConexaoNaPorta) it.usandoTablet else false,
            )
        }
        return marcouAgora
    }

    /** A próxima captura deve ser endereçada ao tablet? */
    fun deveUsarTablet(): Boolean = resumo.usandoTablet

    /** Recomeça a confiar no tablet (receptor subiu de novo, sessão nova). */
    fun voltarAConfiarNoTablet() {
        mudar { it.copy(usandoTablet = true) }
    }

    fun recontarParadas() {
        mudar { it.copy(paradasNoTablet = pendentes().size) }
    }

    /** Fim de sessão: zera o que é da perícia e ESQUECE as credenciais em memória. */
    fun encerrarSessao() {
        credenciais.clear()
        marcadas.clear()
        emEnvio.clear()
        mudar { Resumo(paradasNoTablet = pendentes().size) }
    }

    companion object {
        /** Sufixo do arquivo que o servidor já confirmou. */
        const val SUFIXO_ENVIADA = ".enviada"
        private val EXTENSOES = listOf("jpg", "png")
    }
}
