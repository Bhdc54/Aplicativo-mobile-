package br.com.facilmova.peritavision.ui

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
import br.com.facilmova.peritavision.R
import br.com.facilmova.peritavision.data.CatalogoPonte
import br.com.facilmova.peritavision.data.ConfiguracoesApp
import br.com.facilmova.peritavision.net.PonteGemini

/** ABA CONFIGURAÇÕES — engrenagem na barra de topo. */
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

            CartaoPv {
                CabecalhoCartao(titulo = "Roteiro do exame", grande = true)
                TextoApoio(
                    "Qual prompt a IA carrega. No padrão a ponte escolhe pelos materiais que o Atena " +
                        "cadastrou no caso e monta a sessão só com aquele roteiro — prompt menor, IA mais atenta. " +
                        "Ninguém pergunta ao perito. Vale para a próxima sessão.",
                )
                Spacer(Modifier.height(10.dp))
                OpcaoRadio(
                    marcada = trilha == ConfiguracoesApp.TRILHA_PERGUNTAR,
                    titulo = "Automático pelos materiais do caso",
                    descricao = "Camisa e calça viram vestuário, faca vira objeto cortante, calcinha vira peça íntima; " +
                        "sem pista, assistente geral — padrão.",
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

            CartaoPv {
                CabecalhoCartao(titulo = "Palavras de comando", grande = true)
                TextoApoio(
                    "A IA não precisa mais de \"PeritaVision\" a cada frase. Diga a palavra no " +
                        "INÍCIO da frase (ou sozinha) e ela muda de modo — e fica nele até você trocar. " +
                        "Tudo por voz: o perito de luvas não toca na tela. Prefira palavras curtas que não " +
                        "apareçam na fala normal da bancada.",
                )
                Spacer(Modifier.height(10.dp))
                var pConversa by remember { mutableStateOf(config.palavraConversa) }
                var pSilencio by remember { mutableStateOf(config.palavraSilencio) }
                var pPausa by remember { mutableStateOf(config.palavraPausa) }
                val padrao = catalogo.palavrasPadrao
                CampoPv(
                    valor = pConversa,
                    onValueChange = { pConversa = it; config.palavraConversa = it },
                    rotulo = "Voltar a conversar (padrão: ${padrao["conversa"] ?: "assistente"})",
                )
                TextoApoio("A IA responde, conduz o roteiro e tira foto a pedido.")
                Spacer(Modifier.height(8.dp))
                CampoPv(
                    valor = pSilencio,
                    onValueChange = { pSilencio = it; config.palavraSilencio = it },
                    rotulo = "Silêncio (padrão: ${padrao["silencio"] ?: "silêncio"})",
                )
                TextoApoio("A IA não fala, mas continua ouvindo, transcrevendo para o laudo e registrando achados. \"O que foi salvo?\" ela lê mesmo assim.")
                Spacer(Modifier.height(8.dp))
                CampoPv(
                    valor = pPausa,
                    onValueChange = { pPausa = it; config.palavraPausa = it },
                    rotulo = "Pausar a gravação (padrão: ${padrao["pausa"] ?: "pausa"})",
                )
                TextoApoio(
                    "Para quando o perito vai fazer outra coisa: nada do que for dito vai para o laudo, " +
                        "nenhum comando executa e a IA não fala. Ela continua ouvindo SÓ para reconhecer a " +
                        "palavra de volta — diga \"assistente\" ou \"silêncio\" para retomar. Tudo por voz, sem tocar na tela.",
                )
            }

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

            TituloSecao("Vídeo dos óculos", "para onde os óculos transmitem")
            var destino by remember { mutableStateOf(config.destinoVideo) }
            var qualidade by remember { mutableStateOf(config.qualidadeVideoTablet) }
            var qualidadeServidor by remember { mutableStateOf(config.qualidadeVideoServidor) }
            var enviarDepois by remember { mutableStateOf(config.videoEnviarDepois) }
            CartaoPv {
                CabecalhoCartao(
                    titulo = "Destino do vídeo",
                    etiqueta = if (destino == ConfiguracoesApp.DESTINO_TABLET) "tablet (teste)" else "servidor",
                    tomEtiqueta = if (destino == ConfiguracoesApp.DESTINO_TABLET) Tom.ATENCAO else Tom.NEUTRO,
                    grande = true,
                )
                TextoApoio(
                    "Hoje os óculos mandam o vídeo pela internet até o servidor, e o tablet puxa de " +
                        "volta para mostrar. No modo TABLET os óculos publicam para o próprio tablet, na " +
                        "Wi-Fi da bancada: sem internet no caminho, mais qualidade, e os arquivos sobem ao " +
                        "servidor quando a perícia termina. Óculos e tablet precisam estar na MESMA rede. " +
                        "Vale para a próxima sessão.",
                )
                Spacer(Modifier.height(10.dp))
                OpcaoRadio(
                    marcada = destino == ConfiguracoesApp.DESTINO_SERVIDOR,
                    titulo = "Servidor (como sempre)",
                    descricao = "RTMP para a VPS · 540p · 15 fps · 1,2 Mbps — o que a internet da bancada aguenta.",
                    onClick = { destino = ConfiguracoesApp.DESTINO_SERVIDOR; config.destinoVideo = destino },
                )
                if (destino == ConfiguracoesApp.DESTINO_SERVIDOR) {
                    Spacer(Modifier.height(10.dp))
                    TextoApoio("Fluidez do movimento (o teto dos óculos é 30 fps):")
                    OpcaoRadio(
                        marcada = qualidadeServidor != ConfiguracoesApp.QUALIDADE_SERVIDOR_FLUIDO,
                        titulo = "540p · 15 fps · 1,2 Mbps (padrão)",
                        descricao = "O que a internet da bancada aguentou em campo. ~9 MB por minuto.",
                        onClick = {
                            qualidadeServidor = ConfiguracoesApp.QUALIDADE_SERVIDOR_PADRAO
                            config.qualidadeVideoServidor = qualidadeServidor
                        },
                    )
                    OpcaoRadio(
                        marcada = qualidadeServidor == ConfiguracoesApp.QUALIDADE_SERVIDOR_FLUIDO,
                        titulo = "540p · 30 fps · 2,5 Mbps (fluido)",
                        descricao = "Movimento no dobro de quadros, mesmo detalhe. Precisa de subida boa: " +
                            "se o vídeo vier travado ou cortado, volte ao padrão. ~19 MB por minuto.",
                        onClick = {
                            qualidadeServidor = ConfiguracoesApp.QUALIDADE_SERVIDOR_FLUIDO
                            config.qualidadeVideoServidor = qualidadeServidor
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                }
                OpcaoRadio(
                    marcada = destino == ConfiguracoesApp.DESTINO_TABLET,
                    titulo = "Tablet, pela Wi-Fi da bancada (teste)",
                    descricao = "Os óculos publicam para este tablet. O cartão de visão mostra quadros/s e tamanho; os segmentos .flv sobem ao servidor no Finalizar.",
                    onClick = { destino = ConfiguracoesApp.DESTINO_TABLET; config.destinoVideo = destino },
                )
                if (destino == ConfiguracoesApp.DESTINO_TABLET) {
                    Spacer(Modifier.height(10.dp))
                    TextoApoio("Quando o vídeo sobe para o servidor:")
                    OpcaoRadio(
                        marcada = !enviarDepois,
                        titulo = "Durante a perícia (padrão)",
                        descricao = "Cada trecho sobe assim que fecha; o Finalizar espera o envio terminar (até 3 min).",
                        onClick = { enviarDepois = false; config.videoEnviarDepois = false },
                    )
                    OpcaoRadio(
                        marcada = enviarDepois,
                        titulo = "Guardar no tablet e enviar depois",
                        descricao = "Faça vários protocolos com o vídeo ficando no tablet; o Finalizar é imediato. " +
                            "Tudo sobe pelo botão Enviar agora, ou sozinho quando o tablet estiver em Wi-Fi sem perícia aberta — " +
                            "e o laudo de cada perícia é gerado só depois que o vídeo dela subir. " +
                            "A IA continua vendo a bancada pela imagem do tablet.",
                        onClick = { enviarDepois = true; config.videoEnviarDepois = true },
                    )
                    Spacer(Modifier.height(10.dp))
                    TextoApoio("Qualidade pedida aos óculos (na rede local dá para pedir mais):")
                    OpcaoRadio(
                        marcada = qualidade == ConfiguracoesApp.QUALIDADE_720P30,
                        titulo = "720p · 30 fps · 3 Mbps",
                        descricao = "Comece por aqui. ~22 MB por minuto.",
                        onClick = { qualidade = ConfiguracoesApp.QUALIDADE_720P30; config.qualidadeVideoTablet = qualidade },
                    )
                    OpcaoRadio(
                        marcada = qualidade == ConfiguracoesApp.QUALIDADE_1080P30,
                        titulo = "1080p · 30 fps · 5 Mbps",
                        descricao = "Se o 720p vier sem falha. ~37 MB por minuto.",
                        onClick = { qualidade = ConfiguracoesApp.QUALIDADE_1080P30; config.qualidadeVideoTablet = qualidade },
                    )
                    OpcaoRadio(
                        marcada = qualidade == ConfiguracoesApp.QUALIDADE_720P_FLUIDO,
                        titulo = "720p · 30 fps · 6 Mbps (fluido)",
                        descricao = "Os mesmos 30 fps do 720p, com o dobro de dados por quadro: " +
                            "movimento liso e sem borrar. ~45 MB por minuto.",
                        onClick = { qualidade = ConfiguracoesApp.QUALIDADE_720P_FLUIDO; config.qualidadeVideoTablet = qualidade },
                    )
                    OpcaoRadio(
                        marcada = qualidade == ConfiguracoesApp.QUALIDADE_1080P_FLUIDO,
                        titulo = "1080p · 30 fps · 8 Mbps (fluido)",
                        descricao = "O mais fluido que os óculos dão. Exige Wi-Fi 5 GHz boa e óculos perto " +
                            "do roteador. ~60 MB por minuto.",
                        onClick = { qualidade = ConfiguracoesApp.QUALIDADE_1080P_FLUIDO; config.qualidadeVideoTablet = qualidade },
                    )
                    Spacer(Modifier.height(6.dp))
                    TextoApoio(
                        "Mais quadros por segundo NÃO deixa mais rápido — deixa o movimento liso. " +
                            "Quem manda na nitidez é o bitrate dividido pelos quadros: por isso as opções " +
                            "\"fluido\" pedem mais dados. Se o vídeo vier travado, desça uma opção.",
                        Tom.NEUTRO,
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
