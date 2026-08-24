package com.example.peritavision.domain

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Cofre de custodia minima.
 * Responsabilidades:
 */
class CofreCustodia(context: Context) {

    /** Destino pre-definido de todas as evidencias capturadas. */
    val diretorioEvidencias: File =
        File(context.getExternalFilesDir(null), "evidencias").apply { mkdirs() }

    /** Log imutavel (append-only) da cadeia de custodia, em JSON Lines. */
    private val arquivoLog: File = File(diretorioEvidencias, "custodia.log.jsonl")

    /**
     * Sela uma evidencia na cadeia de custodia e devolve o hash do elo criado.
     * O elo = SHA-256(hashAnterioR + payloadCanonico).
     */
    @Synchronized
    fun registrar(ev: Evidencia): String {
        val hashAnterior = ultimoHashDaCorrente()
        val seq = contarLinhas() + 1

        // payload canonico (ordem fixa de campos) para o encadeamento ser reproduzivel
        val payload = buildString {
            append(seq); append('|')
            append(ev.id); append('|')
            append(ev.tipo.name); append('|')
            append(ev.sha256); append('|')
            append(ev.timestampUtc); append('|')
            append(ev.casoId ?: ""); append('|')
            append(ev.geo?.let { "${it.lat},${it.lon}" } ?: "")
        }
        val hashElo = Hashing.sha256(hashAnterior + payload)

        val linha = JSONObject().apply {
            put("seq", seq)
            put("evidenciaId", ev.id)
            put("casoId", ev.casoId ?: JSONObject.NULL)
            put("tipo", ev.tipo.name)
            put("arquivo", File(ev.caminhoArquivo).name)
            put("sha256", ev.sha256)
            put("tamanhoBytes", ev.tamanhoBytes)
            put("timestampUtc", ev.timestampUtc)
            put("gps", ev.geo?.let { "${it.lat},${it.lon}" } ?: JSONObject.NULL)
            put("hashAnterior", hashAnterior)
            put("hashElo", hashElo)
        }
        arquivoLog.appendText(linha.toString() + "\n")
        return hashElo
    }

    /** Verifica se a corrente de hash esta integra (nenhuma linha adulterada). */
    fun verificarIntegridade(): Boolean {
        if (!arquivoLog.exists()) return true
        var hashEsperado = HASH_GENESE
        // Laco normal (readLines) para permitir o 'return' ao achar adulteracao —
        // dentro de forEachLine o return nao-local nao e permitido.
        for (linha in arquivoLog.readLines()) {
            if (linha.isBlank()) continue
            val o = JSONObject(linha)
            if (o.getString("hashAnterior") != hashEsperado) return false
            hashEsperado = o.getString("hashElo")
        }
        return true
    }

    private fun ultimoHashDaCorrente(): String {
        if (!arquivoLog.exists()) return HASH_GENESE
        var ultimo = HASH_GENESE
        arquivoLog.forEachLine { linha ->
            if (linha.isNotBlank()) ultimo = JSONObject(linha).getString("hashElo")
        }
        return ultimo
    }

    private fun contarLinhas(): Int {
        if (!arquivoLog.exists()) return 0
        var n = 0
        arquivoLog.forEachLine { if (it.isNotBlank()) n++ }
        return n
    }

    companion object {
        /** Elo raiz da corrente (bloco genese). */
        private const val HASH_GENESE = "0000000000000000000000000000000000000000000000000000000000000000"

        /** Timestamp UTC ISO-8601 para carimbar cada evidencia. */
        fun agoraUtc(): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            return fmt.format(System.currentTimeMillis())
        }
    }
}
