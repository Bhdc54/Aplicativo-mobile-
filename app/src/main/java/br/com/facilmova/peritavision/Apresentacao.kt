package br.com.facilmova.peritavision

/*
 * COMO AS COISAS APARECEM PARA O PERITO — 12/09/2026.
 *
 * A tela da bancada mostrava dado cru vindo do ATENA e da narração: data em
 * ISO ("2026-02-04T04:00:00.000+00:00"), objeto JSON inteiro no lugar de um
 * objetivo de exame ({"numeroProtocolo":"054466/2026"}) e falas de comando
 * como consideração do laudo ("Sim.", "Eu quero capturar.", "firm").
 * Nada disso é erro de tela: é dado que chega assim. Aqui fica a tradução
 * para o que o perito — e quem estiver assistindo a uma perícia — deve ler.
 */

/** "2026-02-04T04:00:00.000+00:00" → "04/02/2026". O que não for data ISO
 *  volta como veio: campo de texto livre do ATENA continua legível. */
fun dataLegivel(bruta: String?): String? {
    val t = bruta?.trim().orEmpty()
    if (t.isEmpty()) return null
    val m = Regex("""^(\d{4})-(\d{2})-(\d{2})""").find(t) ?: return t
    val (ano, mes, dia) = m.destructured
    return "$dia/$mes/$ano"
}

/** Texto que é JSON cru, ou sobra de serialização, não vai para a tela. */
private fun ehLixoDeDado(t: String): Boolean {
    val s = t.trim()
    if (s.length < 2) return true
    if (s.startsWith("{") || s.startsWith("[")) return true      // objeto/array inteiro
    if (s.contains("\":")) return true                            // pedaço de JSON
    if (s.equals("null", true) || s.equals("undefined", true)) return true
    return false
}

/** Lista vinda do ATENA pronta para a tela: sem JSON cru, sem repetição. */
fun paraTela(itens: List<String>): List<String> =
    itens.map { it.trim() }.filterNot { it.isEmpty() || ehLixoDeDado(it) }.distinct()

/*
 * FALA DE COMANDO, não consideração do laudo. Mesma regra do servidor
 * (backend/src/domain/fala.ts): o que o perito diz PARA a IA — "captura",
 * "silêncio", "Sim.", "eu vou capturar aqui" — não descreve material nenhum
 * e não pode entrar no laudo como consideração. Uma frase descritiva de três
 * palavras ou mais passa; o perito revisa no painel de qualquer jeito.
 */
private val COMANDOS = Regex(
    """\b(captur\w*|capta\w*|foto\w*|fotograf\w*|registr\w*|finaliz\w*|encerr\w*|descart\w*|capture|photo)\b""",
    RegexOption.IGNORE_CASE,
)
private val MODOS = Regex("""^\s*(assistente|sil[eê]ncio|pausa|privad[ao]|conversa)\b""", RegexOption.IGNORE_CASE)
private val CONFIRMACOES = Regex(
    """^\s*(sim|n[aã]o|ok|okay|t[aá]|certo|isso|confirmo|confirmado|pode|beleza|pronto|feito|entendi|obrigad[ao])[\s.!,]*$""",
    RegexOption.IGNORE_CASE,
)
private val ORDEM = Regex(
    """\b(vou|vamos|pode|posso|quero|vai|deixa)\s+(\w+\s+){0,2}(captur\w*|capta\w*|tirar\s+(uma\s+|a\s+)?foto|fotograf\w*|registr\w*)""",
    RegexOption.IGNORE_CASE,
)

fun ehFalaDeComando(texto: String?): Boolean {
    val t = texto?.trim().orEmpty()
    if (t.isEmpty()) return true
    if (MODOS.containsMatchIn(t)) return true
    if (CONFIRMACOES.containsMatchIn(t)) return true
    if (ORDEM.containsMatchIn(t)) return true
    val palavras = t.split(Regex("""\s+""")).filter { it.isNotBlank() }
    if (palavras.size <= 5 && COMANDOS.containsMatchIn(t)) return true
    return palavras.size <= 2
}

/** Narração pronta para a seção de considerações: só o que descreve algo. */
fun narracaoParaOLaudo(trechos: List<String>): List<String> =
    trechos.filterNot { ehFalaDeComando(it) }
