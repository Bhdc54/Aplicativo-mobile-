package br.com.facilmova.peritavision.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** CONFIGURAÇÕES DO APP (aba "Configurações", engrenagem na barra de topo). */
class ConfiguracoesApp(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("pv_configuracoes", Context.MODE_PRIVATE)

    // Segredos (senha do Wi-Fi) ficam cifrados; se o cofre não abrir, cai no prefs comum.
    private val segredos: SharedPreferences = runCatching {
        EncryptedSharedPreferences.create(
            context,
            "pv_segredos",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrDefault(prefs)

    init {
        // Migração única: a senha gravada em claro vai para o cofre e sai do prefs comum.
        if (segredos !== prefs && prefs.contains(CHAVE_WIFI_SENHA)) {
            val antiga = prefs.getString(CHAVE_WIFI_SENHA, "") ?: ""
            if (runCatching { segredos.edit().putString(CHAVE_WIFI_SENHA, antiga).commit() }.getOrDefault(false)) {
                prefs.edit().remove(CHAVE_WIFI_SENHA).apply()
            }
        }
    }

    /** "perguntar" ou o id de uma trilha do catálogo. */
    var trilha: String
        get() = prefs.getString(CHAVE_TRILHA, TRILHA_PERGUNTAR) ?: TRILHA_PERGUNTAR
        set(v) = prefs.edit().putString(CHAVE_TRILHA, v.ifBlank { TRILHA_PERGUNTAR }).apply()

    /** Nome do modelo; vazio = deixa a ponte usar o padrão dela. */
    var modelo: String
        get() = prefs.getString(CHAVE_MODELO, "") ?: ""
        set(v) = prefs.edit().putString(CHAVE_MODELO, v.trim()).apply()

    var ultimaMatricula: String
        get() = prefs.getString(CHAVE_MATRICULA, "") ?: ""
        set(v) = prefs.edit().putString(CHAVE_MATRICULA, v.trim()).apply()

    /** Wi-Fi do local, como digitado em Configurações. */
    var wifiSsid: String
        get() = prefs.getString(CHAVE_WIFI_SSID, "") ?: ""
        set(v) = prefs.edit().putString(CHAVE_WIFI_SSID, v).apply()
    var wifiSenha: String
        get() = segredos.getString(CHAVE_WIFI_SENHA, "") ?: ""
        set(v) = segredos.edit().putString(CHAVE_WIFI_SENHA, v).apply()

    var palavraConversa: String
        get() = prefs.getString(CHAVE_PAL_CONVERSA, "") ?: ""
        set(v) = prefs.edit().putString(CHAVE_PAL_CONVERSA, v.trim()).apply()
    var palavraSilencio: String
        get() = prefs.getString(CHAVE_PAL_SILENCIO, "") ?: ""
        set(v) = prefs.edit().putString(CHAVE_PAL_SILENCIO, v.trim()).apply()
    var palavraPausa: String
        get() = prefs.getString(CHAVE_PAL_PAUSA, "") ?: ""
        set(v) = prefs.edit().putString(CHAVE_PAL_PAUSA, v.trim()).apply()

    /** Perfil de captura quando o destino é o tablet. Na LAN dá para pedir mais
     *  que os 540p/15 fps que a VPS aguentava. */
    var qualidadeVideoTablet: String
        get() = prefs.getString(CHAVE_QUALIDADE_TABLET, QUALIDADE_720P30) ?: QUALIDADE_720P30
        set(v) = prefs.edit().putString(CHAVE_QUALIDADE_TABLET, v).apply()

    var qualidadeVideoServidor: String
        get() = prefs.getString(CHAVE_QUALIDADE_SERVIDOR, QUALIDADE_SERVIDOR_PADRAO) ?: QUALIDADE_SERVIDOR_PADRAO
        set(v) = prefs.edit().putString(CHAVE_QUALIDADE_SERVIDOR, v).apply()

    var videoEnviarDepois: Boolean
        get() = prefs.getBoolean(CHAVE_VIDEO_ENVIAR_DEPOIS, false)
        set(v) = prefs.edit().putBoolean(CHAVE_VIDEO_ENVIAR_DEPOIS, v).apply()

    /** O vídeo dos óculos vai sempre para o tablet, pela Wi-Fi da bancada. */
    val videoNoTablet: Boolean get() = true

    /** Mapa modo → palavra, só com as que o perito preencheu. */
    fun palavrasParaPonte(): Map<String, String> = buildMap {
        palavraConversa.takeIf { it.isNotBlank() }?.let { put("conversa", it) }
        palavraSilencio.takeIf { it.isNotBlank() }?.let { put("silencio", it) }
        palavraPausa.takeIf { it.isNotBlank() }?.let { put("pausa", it) }
    }

    /** O que vai no {tipo:'iniciar'} da ponte: null = "não fixei, escolha pelo caso". */
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
        private const val CHAVE_QUALIDADE_TABLET = "video.qualidade_tablet"
        private const val CHAVE_QUALIDADE_SERVIDOR = "video.qualidade_servidor"
        private const val CHAVE_VIDEO_ENVIAR_DEPOIS = "video.enviar_depois"
        const val QUALIDADE_720P30 = "720p30"
        const val QUALIDADE_1080P30 = "1080p30"
        const val QUALIDADE_720P_FLUIDO = "720p30f"
        const val QUALIDADE_1080P_FLUIDO = "1080p30f"
        const val QUALIDADE_SERVIDOR_PADRAO = "540p15"
        const val QUALIDADE_SERVIDOR_FLUIDO = "540p30f"
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
