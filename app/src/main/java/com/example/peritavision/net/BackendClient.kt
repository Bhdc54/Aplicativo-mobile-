package com.example.peritavision.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.sync.withLock
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente HTTP do backend PeritaVision (API B).
 * Usa HttpURLConnection e org.json — ambos ja vem no Android, sem dependencia
 */
class BackendClient(var baseUrl: String) {

    /** Token JWT do perito, preenchido pelo [login]. */
    var token: String? = null
        private set
    /** Credenciais do último login — para RENOVAR o token sozinho. O JWT do
     *  backend expira (15 min por padrão) e uma perícia dura mais que isso:
     *  em campo (03/09/2026) o Finalizar caiu com 401 depois de uma hora de
     *  bancada, e a narração dos últimos 45 min foi recusada em silêncio.
     *  Agora: token com mais de RENOVAR_APOS_MS é renovado ANTES da chamada,
     *  e um 401 inesperado renova e repete a chamada uma vez. */
    private var credenciais: Pair<String, String>? = null
    @Volatile private var tokenObtidoEm = 0L
    private val RENOVAR_APOS_MS = 10 * 60_000L
    private val travaLogin = kotlinx.coroutines.sync.Mutex()

    /** Dados de uma captura autorizada pelo backend (token de USO UNICO). */
    data class CredencialCaptura(
        val requestId: String,
        val webhookUrl: String,
        val authToken: String,
    )

    // ------------------------------------------------------------------------
    // Fluxo
    // ------------------------------------------------------------------------

    suspend fun login(matricula: String, senha: String): String {
        val corpo = JSONObject().put("matricula", matricula).put("senha", senha)
        val r = postJson("/v1/auth/login", corpo, autenticado = false)
        val t = r.optString("token").takeIf { it.isNotBlank() }
            ?: throw BackendException("login sem token: $r")
        token = t
        tokenObtidoEm = System.currentTimeMillis()
        credenciais = matricula to senha
        return t
    }

    /** Renova o token com as credenciais guardadas (uma renovação por vez). */
    private suspend fun renovarToken(motivo: String) {
        val (m, s) = credenciais ?: return
        travaLogin.withLock {
            // outra corrotina pode ter renovado enquanto esperávamos
            if (System.currentTimeMillis() - tokenObtidoEm < 30_000L) return
            Log.i(TAG, "renovando token ($motivo)")
            login(m, s)
        }
    }

    /** Garante token fresco antes de uma chamada autenticada. */
    private suspend fun garantirToken() {
        if (credenciais == null) return
        if (System.currentTimeMillis() - tokenObtidoEm > RENOVAR_APOS_MS) {
            runCatching { renovarToken("token com mais de ${RENOVAR_APOS_MS / 60_000} min") }
                .onFailure { Log.w(TAG, "renovação falhou: ${it.message}") }
        }
    }

    /** Executa a chamada; num 401, renova o token e tenta UMA vez mais. */
    private suspend fun <T> comReautenticacao(bloco: suspend () -> T): T {
        garantirToken()
        return try {
            bloco()
        } catch (e: BackendException) {
            if (e.codigo != 401 || credenciais == null) throw e
            renovarToken("401 do backend")
            bloco()
        }
    }

    /** Tudo que o Atena devolveu sobre o caso ao resolver o protocolo —
     *  usado como contexto do assistente IA, não só o id. */
    data class CasoAtena(
        val id: String,
        val numeroProtocolo: String,
        val prioridade: String?,
        val prazoHoras: Int?,
        val naturezas: List<String>,
        val autoridade: String?,
        val materiais: List<String>,
        val exames: List<String>,
        val unidadeRequisitante: String?,
        val dataOcorrencia: String?,
        val statusProtocolo: String?,
        val areaAtuacao: String?,
        /** "nome (papel)" por envolvido — ex.: "Franciane de Jesus Alves (envolvida)". */
        val envolvidos: List<String>,
        val documentoPrincipal: String?,
        /** Id para textoDocumento() — o conteúdo da requisição anexada. */
        val documentoId: String?,
    )

    /** Resolve o protocolo (numero do caso) e devolve o caso completo do Atena. */
    suspend fun resolverProtocolo(protocolo: String): CasoAtena {
        val r = postJson("/v1/casos/resolver", JSONObject().put("numeroProtocolo", protocolo))
        val id = r.optString("id").takeIf { it.isNotBlank() }
            ?: throw BackendException("caso sem id: $r")
        fun lista(chave: String): List<String> {
            val a = r.optJSONArray(chave) ?: return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                when (val v = a.opt(i)) {
                    is String -> v
                    is JSONObject -> buildString {
                        append(v.optString("descricao"))
                        val q = v.optInt("quantidade", 0)
                        if (q > 0) append(" (quantidade: $q)")
                        v.optString("lacreEntrada").takeIf { it.isNotBlank() }
                            ?.let { append(", lacre de entrada $it") }
                    }
                    else -> null
                }
            }.filter { it.isNotBlank() }
        }
        return CasoAtena(
            id = id,
            numeroProtocolo = r.optString("numeroProtocolo", protocolo),
            prioridade = r.optString("prioridade").takeIf { it.isNotBlank() },
            prazoHoras = if (r.has("prazoHoras") && !r.isNull("prazoHoras")) r.optInt("prazoHoras") else null,
            naturezas = lista("naturezas"),
            autoridade = r.optString("autoridade").takeIf { it.isNotBlank() },
            materiais = lista("materiais"),
            exames = lista("exames"),
            unidadeRequisitante = r.optString("unidadeRequisitante").takeIf { it.isNotBlank() },
            dataOcorrencia = r.optString("dataOcorrencia").takeIf { it.isNotBlank() },
            statusProtocolo = r.optString("statusProtocolo").takeIf { it.isNotBlank() },
            areaAtuacao = r.optString("areaAtuacao").takeIf { it.isNotBlank() },
            envolvidos = r.optJSONArray("envolvidos")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val p = a.optJSONObject(i) ?: return@mapNotNull null
                    val nome = p.optString("nome").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val papel = p.optString("papel").takeIf { it.isNotBlank() }
                    if (papel != null) "$nome ($papel)" else nome
                }
            } ?: emptyList(),
            documentoPrincipal = r.optJSONObject("documentoPrincipal")?.let { d ->
                buildString {
                    d.optString("tipo").takeIf { it.isNotBlank() }?.let { append(it).append(" ") }
                    d.optString("numero").takeIf { it.isNotBlank() }?.let { append("nº ").append(it) }
                    d.optString("data").takeIf { it.isNotBlank() }?.let { append(" de ").append(it) }
                }.trim().ifBlank { null }
            },
            documentoId = r.optString("documentoId").takeIf { it.isNotBlank() },
        )
    }

    /** Requisição do Atena: o texto e, quando não deu, o MOTIVO. O motivo vai
     *  para o contexto da IA — sem ele o assistente só ficava sem o texto e
     *  não tinha como dizer ao perito que não havia requisição. */
    data class Requisicao(val texto: String?, val aviso: String?)

    suspend fun textoDocumento(documentoId: String): Requisicao {
        return try {
            val r = getJson("/v1/casos/documento/$documentoId/texto")
            Requisicao(
                r.optString("texto").takeIf { it.isNotBlank() },
                r.optString("aviso").takeIf { it.isNotBlank() },
            )
        } catch (e: Exception) {
            Requisicao(null, e.message ?: "falha ao baixar o documento no Atena")
        }
    }

    /** Pega o primeiro perfil disponivel (MVP: so existe um). */
    suspend fun primeiroPerfil(): String {
        val a = getArray("/v1/perfis")
        if (a.length() == 0) throw BackendException("nenhum perfil cadastrado")
        return a.getJSONObject(0).getString("id")
    }

    data class SessaoAberta(
        val sessaoId: String,
        val rtmpUrl: String?,
        /** true = o backend achou uma sessão ainda aberta deste caso/perito e a devolveu (retomada). */
        val retomada: Boolean = false,
        /** fotos já recebidas nessa sessão, para o contador continuar de onde parou. */
        val fotosRecebidas: Int = 0,
    )

    /** @param matriculaPerito quem está na bancada — separa os laudos por perito. */
    suspend fun abrirSessao(casoId: String, perfilId: String, matriculaPerito: String): SessaoAberta {
        val corpo = JSONObject().put("casoId", casoId).put("perfilId", perfilId)
            .put("matriculaPerito", matriculaPerito)
        val r = postJson("/v1/sessoes", corpo)
        val id = r.optString("sessaoId").takeIf { it.isNotBlank() }
            ?: throw BackendException("sessao sem id: $r")
        return SessaoAberta(
            sessaoId = id,
            rtmpUrl = r.optString("rtmpUrl").takeIf { it.isNotBlank() },
            retomada = r.optBoolean("retomada", false),
            fotosRecebidas = r.optInt("fotosRecebidas", 0),
        )
    }

    /**
     * Pede ao backend autorizacao para UMA captura. O token vale uma vez so —
     * chame de novo a cada foto.
     */
    suspend fun solicitarCaptura(sessaoId: String): CredencialCaptura {
        val r = postJson("/v1/sessoes/$sessaoId/capturas/solicitar", JSONObject())
        val requestId = r.optString("requestId")
        val webhookUrl = r.optString("webhookUrl")
        val authToken = r.optString("authToken")
        if (requestId.isBlank() || webhookUrl.isBlank()) {
            throw BackendException("resposta de captura incompleta: $r")
        }
        return CredencialCaptura(requestId, webhookUrl, authToken)
    }

    /** Registra um evento da sessao (ex.: comando de voz reconhecido). */
    suspend fun registrarEvento(
        sessaoId: String, tipo: String, intencao: String, origem: String,
        payload: JSONObject? = null,
    ) {
        val corpo = JSONObject()
            .put("tipo", tipo).put("intencao", intencao).put("origem", origem)
        if (payload != null) corpo.put("payload", payload)
        runCatching { postJson("/v1/sessoes/$sessaoId/eventos", corpo) }
            .onFailure { Log.w(TAG, "evento nao registrado: ${it.message}") }
    }

    /** Deposita um trecho da fala do perito (transcrita pelo Gemini Live) na
     *  narração da sessão — é a fonte primária que preenche o laudo. */
    suspend fun narrar(sessaoId: String, texto: String) {
        val t = texto.trim()
        if (t.isEmpty()) return
        runCatching { postJson("/v1/sessoes/$sessaoId/narracao", JSONObject().put("texto", t.take(4000))) }
            .onFailure { Log.w(TAG, "narracao nao registrada: ${it.message}") }
    }

    /** Finaliza a sessao; o backend monta o laudo e devolve o id dele. */
    suspend fun finalizarSessao(sessaoId: String): String {
        val r = postJson("/v1/sessoes/$sessaoId/finalizar", JSONObject())
        return r.optString("laudoId")
    }

    /** Emite o webhook para os óculos fotografarem o LACRE (pré-sessão). */
    suspend fun solicitarLeituraLacre(): CredencialCaptura {
        val r = postJson("/v1/lacre/leituras", JSONObject())
        return CredencialCaptura(
            requestId = r.getString("leituraId"),
            webhookUrl = r.getString("webhookUrl"),
            authToken = r.getString("authToken"),
        )
    }

    /** Ficha do caso montada a partir do código de barras do lacre + Atena. */
    data class FichaLacre(
        val codigo: String,
        val numeroProtocolo: String,
        val solicitante: String?,
        val unidadeRequisitante: String?,
        val vitima: String?,
        val dataOcorrencia: String?,
        val quantidadeMateriais: Int,
        val naturezas: List<String>,
        val materiais: List<String>,
    )

    /** Poll da leitura: devolve null enquanto processa; lança se status=erro. */
    suspend fun obterLeituraLacre(leituraId: String): FichaLacre? {
        val r = getJson("/v1/lacre/leituras/$leituraId")
        when (r.optString("status")) {
            "ok" -> {}
            "erro" -> throw BackendException(r.optString("erro").ifBlank { "falha na leitura do lacre" })
            else -> return null // aguardando_foto | processando
        }
        val f = r.getJSONObject("ficha")
        val naturezas = f.optJSONArray("naturezas")
        val materiais = f.optJSONArray("materiais")
        return FichaLacre(
            codigo = f.optString("codigo"),
            numeroProtocolo = f.optString("numeroProtocolo"),
            solicitante = f.optString("solicitante").ifBlank { null },
            unidadeRequisitante = f.optString("unidadeRequisitante").ifBlank { null },
            vitima = f.optString("vitima").ifBlank { null },
            dataOcorrencia = f.optString("dataOcorrencia").ifBlank { null },
            quantidadeMateriais = f.optInt("quantidadeMateriais"),
            naturezas = (0 until (naturezas?.length() ?: 0)).map { naturezas!!.optString(it) },
            materiais = (0 until (materiais?.length() ?: 0)).map { i ->
                val m = materiais!!.getJSONObject(i)
                val lacre = m.optString("lacreEntrada").ifBlank { null }
                m.optString("descricao") + (lacre?.let { " — lacre $it" } ?: "")
            },
        )
    }

    /** Um trecho do laudo, com a origem (atena | perito | ia) para a etiqueta. */
    data class TrechoLaudo(val secao: Int, val titulo: String, val origem: String, val texto: String)

    /**
     * Rascunho do laudo, seção a seção (GET /v1/laudos/:id). Usado pelo cartão
     * "Laudo" da tela para mostrar o texto sendo montado logo após o finalizar.
     */
    suspend fun obterLaudo(laudoId: String): List<TrechoLaudo> {
        val r = getJson("/v1/laudos/$laudoId")
        val trechos = r.optJSONArray("trechos") ?: return emptyList()
        return (0 until trechos.length()).map { i ->
            val t = trechos.getJSONObject(i)
            TrechoLaudo(
                secao = t.optInt("secao"),
                titulo = t.optString("titulo"),
                origem = t.optString("origem"),
                texto = t.optString("texto"),
            )
        }
    }

    /**
     * Sobe uma foto que esta no CELULAR para o webhook (modo PHONE de teste).
     * No modo MENTRA quem sobe o JPEG sao os proprios oculos, por Wi-Fi.
     */
    suspend fun enviarFoto(cred: CredencialCaptura, arquivo: File): Unit = withContext(Dispatchers.IO) {
        val fronteira = "----peritavision${System.currentTimeMillis()}"
        val conn = abrir(URL(cred.webhookUrl), "POST").apply {
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$fronteira")
            if (cred.authToken.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${cred.authToken}")
            }
            doOutput = true
        }
        DataOutputStream(conn.outputStream).use { out ->
            out.writeBytes("--$fronteira\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"photo\"; filename=\"${arquivo.name}\"\r\n")
            out.writeBytes("Content-Type: image/jpeg\r\n\r\n")
            arquivo.inputStream().use { it.copyTo(out) }
            out.writeBytes("\r\n--$fronteira--\r\n")
        }
        conferir(conn, "upload da foto")
        conn.disconnect()
    }

    // ------------------------------------------------------------------------
    // Encanamento HTTP
    // ------------------------------------------------------------------------

    private fun abrir(url: URL, metodo: String): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            requestMethod = metodo
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
        }

    private suspend fun postJson(
        caminho: String,
        corpo: JSONObject,
        autenticado: Boolean = true,
    ): JSONObject = if (autenticado) comReautenticacao { postJsonCru(caminho, corpo, true) }
        else postJsonCru(caminho, corpo, false)

    private suspend fun postJsonCru(
        caminho: String,
        corpo: JSONObject,
        autenticado: Boolean,
    ): JSONObject = withContext(Dispatchers.IO) {
        val conn = abrir(URL(baseUrl.trimEnd('/') + caminho), "POST").apply {
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (autenticado) autenticar(this)
            doOutput = true
        }
        conn.outputStream.use { it.write(corpo.toString().toByteArray(Charsets.UTF_8)) }
        val texto = conferir(conn, "POST $caminho")
        conn.disconnect()
        if (texto.isBlank()) JSONObject() else JSONObject(texto)
    }

    private suspend fun getJson(caminho: String): JSONObject = comReautenticacao {
        getJsonCru(caminho)
    }

    private suspend fun getJsonCru(caminho: String): JSONObject = withContext(Dispatchers.IO) {
        val conn = abrir(URL(baseUrl.trimEnd('/') + caminho), "GET").apply { autenticar(this) }
        val texto = conferir(conn, "GET $caminho")
        conn.disconnect()
        if (texto.isBlank()) JSONObject() else JSONObject(texto)
    }

    private suspend fun getArray(caminho: String): JSONArray = comReautenticacao {
        getArrayCru(caminho)
    }

    private suspend fun getArrayCru(caminho: String): JSONArray = withContext(Dispatchers.IO) {
        val conn = abrir(URL(baseUrl.trimEnd('/') + caminho), "GET").apply { autenticar(this) }
        val texto = conferir(conn, "GET $caminho")
        conn.disconnect()
        if (texto.isBlank()) JSONArray() else JSONArray(texto)
    }

    private fun autenticar(conn: HttpURLConnection) {
        token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
    }

    /** Le a resposta; se o status for >= 400, lanca com a mensagem do backend. */
    private fun conferir(conn: HttpURLConnection, oque: String): String {
        val codigo = conn.responseCode
        val fluxo = if (codigo in 200..299) conn.inputStream else conn.errorStream
        val texto = fluxo?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (codigo !in 200..299) {
            val motivo = runCatching { JSONObject(texto).optString("erro") }.getOrNull()
            throw BackendException("$oque falhou ($codigo): ${motivo.orEmpty().ifBlank { texto }}", codigo)
        }
        return texto
    }

    companion object { private const val TAG = "BackendClient" }
}

class BackendException(mensagem: String, val codigo: Int = 0) : Exception(mensagem)
