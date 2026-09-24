package br.com.facilmova.peritavision.domain

/** Modelos de dominio — independentes do hardware (celular ou oculos Mentra). */

/** Tipo de evidencia capturada. */
enum class TipoEvidencia { FOTO, VIDEO, AUDIO }

/** Coordenada GPS. */
data class Geo(val lat: Double, val lon: Double, val precisaoM: Float?)

data class Evidencia(
    val id: String,
    val casoId: String?,
    val tipo: TipoEvidencia,
    val caminhoArquivo: String,
    val sha256: String,
    val tamanhoBytes: Long,
    val timestampUtc: String,
    val geo: Geo?
)

/** Sintese do caso devolvida pela consulta ao Atena (mockada por enquanto). */
data class SinteseCaso(
    val codigoBarras: String,
    val numeroCaso: String,
    val tipoExame: String,
    val quesitos: List<String>,
    val prazo: String,
    val historicoCustodia: String
)
