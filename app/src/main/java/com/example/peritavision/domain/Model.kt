package com.example.peritavision.domain

/**
 * Modelos de dominio — independentes do hardware (celular ou oculos Mentra).
 * Quando os oculos chegarem, nada aqui muda.
 */

/** Tipo de evidencia capturada. */
enum class TipoEvidencia { FOTO, VIDEO, AUDIO }

/**
 * Coordenada GPS. MANTIDA no modelo de proposito, mas NAO E MAIS COLETADA
 * (decisao de 19/08/2026): o campo segue no payload canonico do log de custodia,
 */
data class Geo(val lat: Double, val lon: Double, val precisaoM: Float?)

/**
 * Uma evidencia capturada com sua cadeia de custodia minima:
 * arquivo em destino fixo + SHA-256 + timestamp UTC + GPS + caso vinculado.
 */
data class Evidencia(
    val id: String,
    val casoId: String?,
    val tipo: TipoEvidencia,
    val caminhoArquivo: String,
    val sha256: String,
    val tamanhoBytes: Long,
    val timestampUtc: String,
    /** Sempre nulo desde 19/08/2026 — ver [Geo]. */
    val geo: Geo?
)

/**
 * Sintese do caso devolvida pela consulta ao Atena (mockada por enquanto).
 * Onda 1 do roadmap: perito le o codigo e recebe o contexto do caso.
 */
data class SinteseCaso(
    val codigoBarras: String,
    val numeroCaso: String,
    val tipoExame: String,
    val quesitos: List<String>,
    val prazo: String,
    val historicoCustodia: String
)
