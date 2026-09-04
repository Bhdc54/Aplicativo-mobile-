package com.example.peritavision.data

import android.content.Context
import android.content.SharedPreferences

/**
 * CONFIGURAÇÕES DO APP (aba "Configurações", engrenagem na barra de topo).
 *
 * Guardadas em SharedPreferences — sobrevivem a fechar o app, mas NÃO vão para
 * o backend: são escolhas do tablet, não da perícia. Hoje duas coisas, ambas
 * do assistente de voz (ponte Gemini Live):
 *
 *  - trilha  → qual roteiro a IA carrega. "perguntar" (padrão) deixa a IA
 *              perguntar ao perito na abertura ("objeto cortante ou peça
 *              íntima?"); um id fixo ("A", "B", "nenhuma"...) pula a pergunta.
 *              A lista de ids vem do catálogo da ponte, então uma trilha nova
 *              registrada lá aparece aqui sem mexer no app.
 *  - modelo  → nome do modelo Gemini Live. Vazio = padrão do servidor.
 *
 * Valem para a PRÓXIMA sessão: a sessão em andamento já nasceu com as
 * escolhas anteriores.
 */
class ConfiguracoesApp(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("pv_configuracoes", Context.MODE_PRIVATE)

    /** "perguntar" ou o id de uma trilha do catálogo. */
    var trilha: String
        get() = prefs.getString(CHAVE_TRILHA, TRILHA_PERGUNTAR) ?: TRILHA_PERGUNTAR
        set(v) = prefs.edit().putString(CHAVE_TRILHA, v.ifBlank { TRILHA_PERGUNTAR }).apply()

    /** Nome do modelo; vazio = deixa a ponte usar o padrão dela. */
    var modelo: String
        get() = prefs.getString(CHAVE_MODELO, "") ?: ""
        set(v) = prefs.edit().putString(CHAVE_MODELO, v.trim()).apply()

    /** Última matrícula que abriu uma perícia neste tablet — o campo já vem
     *  preenchido na próxima vez (o tablet é compartilhado, mas quem usa
     *  costuma ser o mesmo perito por dias). */
    var ultimaMatricula: String
        get() = prefs.getString(CHAVE_MATRICULA, "") ?: ""
        set(v) = prefs.edit().putString(CHAVE_MATRICULA, v.trim()).apply()

    /** Wi-Fi do local, como digitado em Configurações. Fica salvo no tablet e
     *  é ENVIADO SOZINHO aos óculos toda vez que eles conectam — o perito
     *  digita a rede uma vez por local, não por perícia. A senha fica em
     *  SharedPreferences privado do app (tablet de bancada, sem conta de
     *  usuário); se isso virar problema, o caminho é EncryptedSharedPreferences. */
    var wifiSsid: String
        get() = prefs.getString(CHAVE_WIFI_SSID, "") ?: ""
        set(v) = prefs.edit().putString(CHAVE_WIFI_SSID, v).apply()
    var wifiSenha: String
        get() = prefs.getString(CHAVE_WIFI_SENHA, "") ?: ""
        set(v) = prefs.edit().putString(CHAVE_WIFI_SENHA, v).apply()

    /** PALAVRAS DE MODO do assistente (03/09/2026 — fim do "PeritaVision" a
     *  cada frase). O perito diz a palavra no início da frase (ou sozinha) e a
     *  IA muda de modo: conversa (responde), silêncio (só ouve e registra) ou
     *  pausa (gravação parada, volta por voz). Vazio = padrão da ponte. */
    var palavraConversa: String
        get() = prefs.getString(CHAVE_PAL_CONVERSA, "") ?: ""
        set(v) = prefs.edit().putString(CHAVE_PAL_CONVERSA, v.trim()).apply()
    var palavraSilencio: String
        get() = prefs.getString(CHAVE_PAL_SILENCIO, "") ?: ""
        set(v) = prefs.edit().putString(CHAVE_PAL_SILENCIO, v.trim()).apply()
    var palavraPausa: String
        get() = prefs.getString(CHAVE_PAL_PAUSA, "") ?: ""
        set(v) = prefs.edit().putString(CHAVE_PAL_PAUSA, v.trim()).apply()

    /** Mapa modo → palavra, só com as que o perito preencheu. */
    fun palavrasParaPonte(): Map<String, String> = buildMap {
        palavraConversa.takeIf { it.isNotBlank() }?.let { put("conversa", it) }
        palavraSilencio.takeIf { it.isNotBlank() }?.let { put("silencio", it) }
        palavraPausa.takeIf { it.isNotBlank() }?.let { put("pausa", it) }
    }

    /** O que vai no {tipo:'iniciar'} da ponte: null = "não fixei, pergunte". */
    fun trilhaParaPonte(): String? = trilha.takeIf { it != TRILHA_PERGUNTAR && it.isNotBlank() }

    companion object {
        const val TRILHA_PERGUNTAR = "perguntar"
        private const val CHAVE_TRILHA = "assistente.trilha"
        private const val CHAVE_MODELO = "assistente.modelo"
        private const val CHAVE_MATRICULA = "pericia.ultima_matricula"
        private const val CHAVE_WIFI_SSID = "oculos.wifi_ssid"
        private const val CHAVE_WIFI_SENHA = "oculos.wifi_senha"
        private const val CHAVE_PAL_CONVERSA = "assistente.palavra_conversa"
        private const val CHAVE_PAL_SILENCIO = "assistente.palavra_silencio"
        private const val CHAVE_PAL_PAUSA = "assistente.palavra_pausa"
        val PALAVRAS_PADRAO = mapOf("conversa" to "assistente", "silencio" to "silêncio", "pausa" to "pausa")
    }
}

/** Uma trilha como a ponte descreve no catálogo. */
data class TrilhaCatalogo(val id: String, val nome: String, val descricao: String)

/** Catálogo devolvido pela ponte em {tipo:'catalogo'}. */
data class CatalogoPonte(
    val trilhas: List<TrilhaCatalogo>,
    val modelos: List<String>,
    val modeloPadrao: String,
    val palavrasPadrao: Map<String, String> = ConfiguracoesApp.PALAVRAS_PADRAO,
) {
    companion object {
        /** Cópia local para a tela não ficar vazia quando a ponte está fora do
         *  alcance. Deve espelhar prompts/index.mjs — se divergir, a ponte manda. */
        val PADRAO = CatalogoPonte(
            trilhas = listOf(
                TrilhaCatalogo("A", "Faca / perfurocortante", "Lesão corporal, homicídio, ameaça — 7 blocos, do lacre à contraprova."),
                TrilhaCatalogo("B", "Peça íntima", "Crimes sexuais — 6 blocos, linguagem clínica, limites interpretativos."),
                TrilhaCatalogo("nenhuma", "Sem roteiro (assistente geral)", "Outros exames: foto, lacre, narração e fechamento, sem passo a passo."),
            ),
            modelos = listOf("gemini-3.1-flash-live-preview"),
            modeloPadrao = "gemini-3.1-flash-live-preview",
        )
    }
}
