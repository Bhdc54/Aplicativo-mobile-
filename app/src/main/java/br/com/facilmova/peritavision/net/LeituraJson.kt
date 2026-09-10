package br.com.facilmova.peritavision.net

import org.json.JSONObject

/**
 * Ler texto de JSON sem cair na armadilha do org.json.
 *
 * `optString("x")` devolve a STRING "null" quando o JSON traz null de verdade;
 * devolve "" só quando a chave não existe. O app lia com
 * `optString(...).takeIf { it.isNotBlank() }` em quase cinquenta lugares, e por
 * isso, em toda perícia, o assistente abria a boca e dizia "o perito da
 * matrícula NULA não está cadastrado, a perícia vai ficar no nome de outro
 * perito": o backend manda matriculaDesconhecida: null exatamente quando a
 * matrícula EXISTE, isso virava a palavra "null", passava pelo isNotBlank() e
 * ligava o aviso.
 *
 * Aqui o isNull cobre chave ausente e null do JSON, e o != "null" cobre o caso
 * em que o texto "null" chegou como texto mesmo — acontece em campos que o
 * banco monta por concatenação.
 */
internal fun JSONObject.texto(chave: String): String? {
    if (isNull(chave)) return null
    return optString(chave).takeIf { it.isNotBlank() && it != "null" }
}

/** Como [texto], mas com valor de reserva quando não há texto — nunca null. */
internal fun JSONObject.textoOu(chave: String, padrao: String = ""): String =
    texto(chave) ?: padrao
