package com.example.peritavision.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.peritavision.R
import com.example.peritavision.data.CatalogoPonte
import com.example.peritavision.data.ConfiguracoesApp
import com.example.peritavision.net.PonteGemini

/**
 * ABA CONFIGURAÇÕES — engrenagem na barra de topo.
 *
 * Hoje só o assistente de voz mora aqui: qual ROTEIRO (trilha) a IA carrega e
 * qual MODELO do Gemini ela usa. As duas listas vêm do catálogo da ponte
 * ({tipo:'catalogo'}), então uma trilha nova registrada em prompts/index.mjs
 * aparece aqui sozinha; se a ponte não responder, mostra a cópia local.
 *
 * Escolha aqui vale para a PRÓXIMA sessão — a que está aberta já nasceu com
 * o prompt e o modelo anteriores.
 */
@Composable
fun TelaConfiguracoes(
    config: ConfiguracoesApp,
    urlPonte: String,
    onVoltar: () -> Unit,
    /** Cartões de óculos e Wi-Fi (vêm da CaptureScreen, com o estado dela). */
    secaoOculos: (@Composable () -> Unit)? = null,
) {
    BackHandler(onBack = onVoltar)

    var trilha by remember { mutableStateOf(config.trilha) }
    var modelo by remember { mutableStateOf(config.modelo) }
    var catalogo by remember { mutableStateOf(CatalogoPonte.PADRAO) }
    var origemCatalogo by remember { mutableStateOf("consultando a ponte...") }
    // "Outro modelo": texto livre, para testar um nome que ainda não está na lista.
    var outroModelo by remember {
        mutableStateOf(if (modelo.isNotBlank() && modelo !in CatalogoPonte.PADRAO.modelos) modelo else "")
    }

    LaunchedEffect(urlPonte) {
        PonteGemini.buscarCatalogo(urlPonte) { recebido ->
            if (recebido != null && recebido.trilhas.isNotEmpty()) {
                catalogo = recebido
                origemCatalogo = "lista atualizada pela ponte"
                if (modelo.isNotBlank() && modelo !in recebido.modelos) outroModelo = modelo
            } else {
                origemCatalogo = if (urlPonte.isBlank()) "ponte não configurada (pv.ponte)"
                else "ponte fora do alcance — lista local"
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Cabeçalho próprio, com o "voltar" — a barra de topo principal fica na tela da bancada.
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onVoltar),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_pv_chevron),
                    contentDescription = "Voltar",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp).rotate(180f),
                )
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text("Configurações", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Óculos, Wi-Fi e assistente de voz",
                    style = MaterialTheme.typography.labelSmall,
                    color = PvTheme.extras.textoSuave,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Column(
            Modifier
                .weight(1f)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            if (secaoOculos != null) {
                TituloSecao("Óculos", "conecte uma vez; vale até desligar")
                secaoOculos()
            }
            TituloSecao("Assistente de voz", origemCatalogo)

            // ── ROTEIRO (trilha) ────────────────────────────────────────────
            CartaoPv {
                CabecalhoCartao(titulo = "Roteiro do exame", grande = true)
                TextoApoio(
                    "Qual prompt a IA carrega. No padrão ela pergunta ao perito na abertura " +
                        "e monta a sessão só com o roteiro daquele exame — prompt menor, IA mais atenta. " +
                        "Vale para a próxima sessão.",
                )
                Spacer(Modifier.height(10.dp))
                OpcaoRadio(
                    marcada = trilha == ConfiguracoesApp.TRILHA_PERGUNTAR,
                    titulo = "Perguntar ao perito na abertura",
                    descricao = "\"O exame é em objeto cortante ou em peça íntima?\" — padrão.",
                    onClick = { trilha = ConfiguracoesApp.TRILHA_PERGUNTAR; config.trilha = trilha },
                )
                catalogo.trilhas.forEach { t ->
                    OpcaoRadio(
                        marcada = trilha == t.id,
                        titulo = "Trilha ${t.id.uppercase()} — ${t.nome}",
                        descricao = t.descricao,
                        onClick = { trilha = t.id; config.trilha = t.id },
                    )
                }
            }

            // ── MODELO ──────────────────────────────────────────────────────
            CartaoPv {
                CabecalhoCartao(
                    titulo = "Modelo do Gemini",
                    etiqueta = if (modelo.isBlank()) "padrão da ponte" else "personalizado",
                    tomEtiqueta = if (modelo.isBlank()) Tom.NEUTRO else Tom.OK,
                    grande = true,
                )
                TextoApoio(
                    "Modelo Live usado na conversa por voz. Nomes \"preview\" mudam — se o " +
                        "Google aposentar um, troque aqui sem novo deploy.",
                )
                Spacer(Modifier.height(10.dp))
                OpcaoRadio(
                    marcada = modelo.isBlank(),
                    titulo = "Padrão da ponte",
                    descricao = catalogo.modeloPadrao.ifBlank { "definido no servidor" },
                    mono = true,
                    onClick = { modelo = ""; config.modelo = ""; outroModelo = "" },
                )
                catalogo.modelos.forEach { m ->
                    OpcaoRadio(
                        marcada = modelo == m,
                        titulo = m,
                        descricao = null,
                        mono = true,
                        onClick = { modelo = m; config.modelo = m; outroModelo = "" },
                    )
                }
                Spacer(Modifier.height(6.dp))
                CampoPv(
                    valor = outroModelo,
                    onValueChange = { digitado ->
                        outroModelo = digitado
                        modelo = digitado.trim()
                        config.modelo = digitado
                    },
                    rotulo = "Outro modelo (nome exato)",
                    endereco = true,
                )
                if (outroModelo.isNotBlank()) {
                    TextoApoio(
                        "A ponte tenta abrir com esse nome; se o Google recusar, o erro aparece " +
                            "na barra de status ao iniciar a sessão.",
                        Tom.ATENCAO,
                    )
                }
            }

            RodapeMarca("Facil Mova")
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** Linha de opção com marcador redondo — lista de rádio no padrão dos cartões. */
@Composable
private fun OpcaoRadio(
    marcada: Boolean,
    titulo: String,
    descricao: String?,
    mono: Boolean = false,
    onClick: () -> Unit,
) {
    val cor = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (marcada) cor.primaryContainer else cor.surface)
            .border(1.dp, if (marcada) cor.primary else cor.outline, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .border(2.dp, if (marcada) cor.primary else cor.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (marcada) Box(Modifier.size(9.dp).clip(CircleShape).background(cor.primary))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                titulo,
                style = if (mono) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                else MaterialTheme.typography.bodyLarge,
                fontWeight = if (marcada) FontWeight.SemiBold else FontWeight.Medium,
                color = cor.onSurface,
            )
            if (!descricao.isNullOrBlank()) {
                Text(
                    descricao,
                    style = MaterialTheme.typography.bodySmall,
                    color = PvTheme.extras.textoSuave,
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}
