package br.com.facilmova.peritavision

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

/** FALA DE COMANDO, não consideração do laudo. */
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
