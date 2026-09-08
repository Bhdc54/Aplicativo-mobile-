// A CUSTÓDIA DAS FOTOS NO TABLET (08/09/2026) — passo 2 da separação.
//
// Esta classe saiu de dentro do CaptureScreen, onde vivia como sete variáveis
// de estado, três funções locais e a decisão de por onde mandar a foto,
// espalhadas por mil linhas de uma função de composição. Os dois erros de
// compilação e o pior bug de campo do dia nasceram dessa mistura, e nada dela
// podia ser testado: qualquer verificação exigia um tablet, óculos e uma
// perícia aberta.
//
// Aqui não há Compose, não há Android e não há rede: só arquivo, estado e
// decisão. É stdlib e java.* puro, de propósito — assim compila e roda fora do
// aparelho, e as regras que decidem se uma evidência entra ou não no laudo
// passaram a ter teste (_to_delete/teste/CustodiaDeFotosTeste.kt).
//
// Quem faz IO de rede continua fora: a tela chama `tomarParaEnvio`, sobe o
// arquivo pelo BackendClient e volta com `confirmarEnvio` ou `devolver`. A
// custódia nunca conversa com o servidor; ela só sabe o que já foi aceito.
package com.example.peritavision.custodia

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

    // ------------------------------------------------------------------------
    // Credenciais: memória E disco
    // ------------------------------------------------------------------------

    /**
     * Guarda a credencial na memória e AO LADO do JPEG que vai chegar.
     *
     * Só em memória, ela morria com o processo: o arquivo ficava na pasta e não
     * havia mais ninguém no mundo capaz de repassá-lo ao servidor, porque o
     * token de uso único tinha ido embora. O formato é chave=valor, uma por
     * linha, e não JSON — quatro campos não justificam uma dependência, e este
     * arquivo precisa continuar sendo stdlib puro para poder ser testado.
     */
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

    /**
     * A credencial de uma captura: da memória, senão do arquivo ao lado do
     * JPEG. `daSessao` não é enfeite — sem ele, a foto que ficou da perícia
     * anterior era repassada durante a perícia seguinte e entrava no contador
     * dela.
     */
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

    fun arquivoDeCredencial(requestId: String): File = File(pasta, "$requestId.cred")

    private fun umaLinha(v: String) = v.replace('\n', ' ').replace('\r', ' ')

    // ------------------------------------------------------------------------
    // Arquivos da foto
    // ------------------------------------------------------------------------

    /** Já existe foto (pendente ou já enviada) para esta captura? */
    fun jaTemFoto(requestId: String): Boolean =
        EXTENSOES.any { e ->
            File(pasta, "$requestId.$e").exists() || File(pasta, "$requestId.$e$SUFIXO_ENVIADA").exists()
        }

    /**
     * Grava a foto e devolve o arquivo, ou null se não deu.
     *
     * Escreve em `.parcial` e só então renomeia: se o processo morrer ou o
     * disco encher no meio, o que sobra é um `.parcial`, que `pendentes()`
     * ignora — nunca um JPEG cortado selado como prova.
     */
    fun gravarFoto(requestId: String, bytes: ByteArray, mime: String): File? {
        val extensao = if (mime.contains("png", true)) "png" else "jpg"
        val destino = File(pasta, "$requestId.$extensao")
        if (jaTemFoto(requestId)) return null
        return runCatching {
            pasta.mkdirs()
            val temporario = File(pasta, "$requestId.parcial")
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

    /**
     * Fotos COMPLETAS ainda não aceitas pelo servidor. Só `.jpg`/`.png`: o
     * `.parcial` fica de fora, e o `.cred` também.
     */
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

    // ------------------------------------------------------------------------
    // Repasse: uma captura de cada vez
    // ------------------------------------------------------------------------

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

    /**
     * O servidor ACEITOU a foto. Marca o arquivo, apaga a credencial (o token
     * era de uso único e já foi gasto), conta, e desfaz a marca no vídeo caso
     * o relógio já tivesse desistido desta captura — chegou foto de verdade,
     * então a marca deixa de existir.
     *
     * Devolve false quando não conseguiu marcar o arquivo como enviado — a
     * foto está no servidor, mas o arquivo continuaria pendente e seria
     * oferecido de novo, então quem chama tem de registrar isso no log.
     */
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

    // ------------------------------------------------------------------------
    // A decisão: tablet ou webhook público
    // ------------------------------------------------------------------------

    /**
     * A foto não chegou no prazo. Devolve true se esta captura virou MARCA no
     * vídeo agora (para o contador da tela subir uma vez só).
     *
     * `houveConexaoNaPorta` é a prova que separa dois problemas diferentes: se
     * ninguém abriu conexão, os óculos não tentaram, e o defeito é endereço ou
     * rede — insistir só perderia as fotos seguintes também, então o caminho
     * volta a ser o webhook público, que já funcionava. Se houve conexão, o
     * endereço está certo e o problema é o envio; aí vale continuar.
     *
     * A credencial NÃO é apagada: os óculos ainda podem entregar a foto
     * atrasada, e quando isso acontece o `confirmarEnvio` desfaz a marca.
     */
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

    /**
     * Fim de sessão: zera o que é da perícia e ESQUECE as credenciais em
     * memória. As de disco ficam — se sobrou foto no tablet, ela ainda precisa
     * poder subir depois, e é a sessão gravada no arquivo que impede que ela
     * seja contada na perícia seguinte.
     */
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
