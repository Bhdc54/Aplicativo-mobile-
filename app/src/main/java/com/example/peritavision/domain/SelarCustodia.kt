package com.example.peritavision.domain

import java.io.File
import java.util.UUID

/**
 * Sela a cadeia de custodia de um arquivo recem-capturado.
 * Este e o pipeline DEVICE-AGNOSTICO: recebe um arquivo (do celular hoje, do
 */
class SelarCustodia(
    private val cofre: CofreCustodia
) {
    fun selar(tipo: TipoEvidencia, arquivo: File, casoId: String?): Evidencia {
        val evidencia = Evidencia(
            id = UUID.randomUUID().toString(),
            casoId = casoId,
            tipo = tipo,
            caminhoArquivo = arquivo.absolutePath,
            sha256 = Hashing.sha256(arquivo),
            tamanhoBytes = arquivo.length(),
            timestampUtc = CofreCustodia.agoraUtc(),
            geo = null
        )
        cofre.registrar(evidencia)
        return evidencia
    }
}
