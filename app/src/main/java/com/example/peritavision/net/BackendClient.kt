package com.example.peritavision.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
        return t
    }

    /** Resolve o protocolo (numero do caso) e devolve o id do caso. */
    suspend fun resolverProtocolo(protocolo: String): String {
        val r = postJson("/v1/casos/resolver", JSONObject().put("numeroProtocolo", protocolo))
        return r.optString("id").takeIf { it.isNotBlank() }
            ?: throw BackendException("caso sem id: $r")
    }

    /** Pega o primeiro perfil disponivel (MVP: so existe um). */
    suspend fun primeiroPerfil(): String {
        val a = getArray("/v1/perfis")
        if (a.length() == 0) throw BackendException("nenhum perfil cadastrado")
        return a.getJSONObject(0).getString("id")
    }

    data class SessaoAberta(val sessaoId: String, val rtmpUrl: String?)

    suspend fun abrirSessao(casoId: String, perfilId: String): SessaoAberta {
        val corpo = JSONObject().put("casoId", casoId).put("perfilId", perfilId)
        val r = postJson("/v1/sessoes", corpo)
        val id = r.optString("sessaoId").takeIf { it.isNotBlank() }
            ?: throw BackendException("sessao sem id: $r")
        return SessaoAberta(id, r.optString("rtmpUrl").takeIf { it.isNotBlank() })
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
    suspend fun registrarEvento(sessaoId: String, tipo: String, intencao: String, origem: String) {
        val corpo = JSONObject()
            .put("tipo", tipo).put("intencao", intencao).put("origem", origem)
        runCatching { postJson("/v1/sessoes/$sessaoId/eventos", corpo) }
            .onFailure { Log.w(TAG, "evento nao registrado: ${it.message}") }
    }

    /** Finaliza a sessao; o backend monta o laudo e devolve o id dele. */
    suspend fun finalizarSessao(sessaoId: String): String {
        val r = postJson("/v1/sessoes/$sessaoId/finalizar", JSONObject())
        return r.optString("laudoId")
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

    private suspend fun getJson(caminho: String): JSONObject = withContext(Dispatchers.IO) {
        val conn = abrir(URL(baseUrl.trimEnd('/') + caminho), "GET").apply { autenticar(this) }
        val texto = conferir(conn, "GET $caminho")
        conn.disconnect()
        if (texto.isBlank()) JSONObject() else JSONObject(texto)
    }

    private suspend fun getArray(caminho: String): JSONArray = withContext(Dispatchers.IO) {
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
            throw BackendException("$oque falhou ($codigo): ${motivo.orEmpty().ifBlank { texto }}")
        }
        return texto
    }

    companion object { private const val TAG = "BackendClient" }
}

class BackendException(mensagem: String) : Exception(mensagem)
