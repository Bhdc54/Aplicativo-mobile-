package br.com.facilmova.peritavision.rtmp

import android.util.Log
import br.com.facilmova.peritavision.domain.Hashing
import br.com.facilmova.peritavision.net.BackendClient
import org.json.JSONObject
import java.io.File

class FilaDeEnvio(private val receptor: ReceptorDeVideo) {

    /** Uma perícia com vídeo esperando para subir. */
    data class Pendente(
        val sessaoId: String,
        val protocolo: String,
        val finalizadaEm: Long,
        val arquivos: Int,
        val bytes: Long,
    ) {
        val megabytes: Double get() = bytes / 1_000_000.0
    }

    private val registro: File get() = File(receptor.pasta, "pendentes.json")

    @Synchronized
    private fun ler(): JSONObject =
        runCatching { JSONObject(registro.takeIf { it.exists() }?.readText().orEmpty().ifBlank { "{}" }) }
            .getOrDefault(JSONObject())

    @Synchronized
    private fun gravar(j: JSONObject) {
        receptor.pasta.mkdirs()
        registro.writeText(j.toString())
    }

    /** A perícia foi finalizada com vídeo guardado: anota o protocolo. */
    fun registrar(sessaoId: String, protocolo: String) {
        val j = ler()
        j.put(sessaoId, JSONObject().put("protocolo", protocolo).put("finalizadaEm", System.currentTimeMillis()))
        gravar(j)
    }

    fun remover(sessaoId: String) {
        val j = ler()
        if (j.has(sessaoId)) { j.remove(sessaoId); gravar(j) }
        // Pasta vazia da sessão não precisa ficar.
        File(receptor.pasta, sessaoId).takeIf { it.isDirectory && (it.list()?.isEmpty() == true) }?.delete()
    }

    /** O que há para subir: toda sessão com .flv no tablet, exceto a que está aberta agora. */
    fun pendentes(excetoSessao: String? = null): List<Pendente> {
        val j = ler()
        val ids = (receptor.sessoesComSegmentos() + j.keys().asSequence().toList()).distinct()
        return ids
            .filter { it != excetoSessao }
            .map { id ->
                val segs = receptor.segmentosDe(id)
                val meta = j.optJSONObject(id)
                Pendente(
                    sessaoId = id,
                    protocolo = meta?.optString("protocolo").orEmpty().ifBlank { "perícia ${id.take(8)}" },
                    finalizadaEm = meta?.optLong("finalizadaEm") ?: (segs.lastOrNull()?.lastModified() ?: 0L),
                    arquivos = segs.size,
                    bytes = segs.sumOf { it.length() },
                )
            }
            .sortedBy { it.finalizadaEm }
    }

    /** `concluidas` = vídeo no servidor E laudo pedido. */
    data class Resultado(
        val concluidas: Int,
        val videosSubidos: Int,
        val falhas: Int,
        val bytes: Long,
        val ultimoErro: String? = null,
    )

    private fun motivo(e: Throwable): String {
        val codigo = (e as? br.com.facilmova.peritavision.net.BackendException)?.codigo ?: 0
        return when {
            codigo == 404 -> "servidor sem a rota de conclusão (HTTP 404) — atualize o servidor"
            codigo in 401..403 -> "sem permissão no servidor (HTTP $codigo)"
            codigo >= 500 -> "erro no servidor (HTTP $codigo)"
            codigo > 0 -> "HTTP $codigo"
            e is java.io.IOException -> "sem rede"
            else -> e.message?.take(80) ?: e.javaClass.simpleName
        }
    }

    /** Sobe tudo, uma perícia por vez, em ordem de finalização. */
    suspend fun enviarTudo(
        backend: BackendClient,
        excetoSessao: String? = null,
        aoProgresso: (String) -> Unit,
    ): Resultado {
        var concluidas = 0; var videosSubidos = 0; var falhas = 0; var bytes = 0L
        var ultimoErro: String? = null
        val lista = pendentes(excetoSessao)
        for ((i, p) in lista.withIndex()) {
            // Sessão só no disco (finalizada antes do registro existir): anota ANTES de subir.
            if (ler().optJSONObject(p.sessaoId) == null) registrar(p.sessaoId, p.protocolo)
            val segs = receptor.segmentosDe(p.sessaoId)
            val totalMb = p.megabytes
            var subiuMb = 0.0
            var tudoOk = true
            for ((k, arquivo) in segs.withIndex()) {
                val inicioMs = arquivo.name.removeSuffix(".flv").toLongOrNull() ?: arquivo.lastModified()
                aoProgresso("Enviando ${p.protocolo} (${i + 1}/${lista.size}) — parte ${k + 1}/${segs.size}, ${"%.0f".format(subiuMb)} de ${"%.0f".format(totalMb)} MB")
                var ok = false
                var semRede = false
                for (tentativa in 1..3) {
                    try {
                        val shaServidor = backend.enviarSegmentoVideo(p.sessaoId, arquivo, inicioMs, lote = true)
                        val shaLocal = Hashing.sha256(arquivo)
                        if (shaServidor.isNotBlank() && !shaServidor.equals(shaLocal, ignoreCase = true)) {
                            throw IllegalStateException("hash divergente (local ${shaLocal.take(12)}, servidor ${shaServidor.take(12)})")
                        }
                        ok = true; break
                    } catch (e: Exception) {
                        Log.w(TAG, "${p.protocolo}/${arquivo.name} tentativa $tentativa: ${e.message}")
                        semRede = e is java.io.IOException
                        ultimoErro = motivo(e)
                        kotlinx.coroutines.delay(2_000L * tentativa)
                    }
                }
                if (ok) {
                    bytes += arquivo.length(); subiuMb += arquivo.length() / 1_000_000.0
                    arquivo.delete()
                } else {
                    tudoOk = false
                    if (semRede) {
                        aoProgresso("Sem rede: o envio de ${p.protocolo} para aqui e continua depois")
                        return Resultado(concluidas, videosSubidos, falhas + 1, bytes, ultimoErro)
                    }
                }
            }
            if (tudoOk) {
                videosSubidos += 1
                // Tudo desta perícia está no servidor: ele consolida e GERA O LAUDO.
                if (segs.isEmpty()) aoProgresso("${p.protocolo}: vídeo já no servidor — pedindo o laudo...")
                val erroConcluir = runCatching { backend.concluirVideo(p.sessaoId) }.exceptionOrNull()
                if (erroConcluir == null) {
                    remover(p.sessaoId)
                    concluidas += 1
                    aoProgresso("${p.protocolo}: vídeo no servidor (${"%.0f".format(totalMb)} MB) — o laudo está sendo gerado")
                } else {
                    falhas += 1
                    ultimoErro = motivo(erroConcluir)
                    Log.w(TAG, "concluir ${p.sessaoId.take(8)}: ${erroConcluir.message}")
                    aoProgresso("${p.protocolo}: vídeo subiu, mas o laudo não foi pedido ($ultimoErro) — tento de novo depois")
                }
            } else {
                falhas += 1
            }
        }
        return Resultado(concluidas, videosSubidos, falhas, bytes, ultimoErro)
    }

    companion object {
        private const val TAG = "PV-FilaDeEnvio"
    }
}
