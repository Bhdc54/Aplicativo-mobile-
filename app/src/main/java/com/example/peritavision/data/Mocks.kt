package com.example.peritavision.data

import com.example.peritavision.domain.SinteseCaso

/**
 * MOCKS temporarios. Substituir por leitura real de barcode (ML Kit) e
 * consulta real ao sistema Atena via API quando o back-end estiver pronto.
 */

/** Leitura de codigo de barras / QR — mockada. */
object BarcodeMock {
    private val codigos = listOf(
        "POLITEC-MT-2026-000123",
        "POLITEC-MT-2026-000488",
        "POLITEC-MT-2026-001547"
    )
    private var i = 0

    /** Simula um "bipe" de leitura, alternando entre alguns codigos. */
    fun ler(): String {
        val codigo = codigos[i % codigos.size]
        i++
        return codigo
    }
}

/** Consulta ao Atena — mockada. Devolve a sintese do caso. */
object AtenaMock {
    fun consultar(codigoBarras: String): SinteseCaso = SinteseCaso(
        codigoBarras = codigoBarras,
        numeroCaso = codigoBarras.substringAfterLast("-"),
        tipoExame = "Exame de vestigios — local de crime",
        quesitos = listOf(
            "Descrever o estado e a posicao dos vestigios encontrados.",
            "Coletar material biologico para exame de DNA, se houver.",
            "Registrar e fotografar cada item com escala metrica."
        ),
        prazo = "10 dias uteis",
        historicoCustodia = "Peca retirada da sala de provas — lacre integro registrado."
    )
}
