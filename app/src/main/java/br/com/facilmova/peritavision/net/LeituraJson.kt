package br.com.facilmova.peritavision.net

import org.json.JSONObject

/** Ler texto de JSON sem cair na armadilha do org.json. */
internal fun JSONObject.texto(chave: String): String? {
    if (isNull(chave)) return null
    return optString(chave).takeIf { it.isNotBlank() && it != "null" }
}

/** Como [texto], mas com valor de reserva quando não há texto — nunca null. */
internal fun JSONObject.textoOu(chave: String, padrao: String = ""): String =
    texto(chave) ?: padrao
