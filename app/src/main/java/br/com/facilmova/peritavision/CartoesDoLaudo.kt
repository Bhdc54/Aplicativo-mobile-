// Cartões do LAUDO e do assistente: o laudo em preenchimento, a ficha do lacre lido e o estado da IA de bancada.
package br.com.facilmova.peritavision

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import br.com.facilmova.peritavision.scan.LeitorCodigo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import br.com.facilmova.peritavision.audio.FeedbackDeVoz
import br.com.facilmova.peritavision.device.GlassesDevice
import br.com.facilmova.peritavision.device.GlassesDeviceFactory
import br.com.facilmova.peritavision.device.GlassesEvent
import br.com.facilmova.peritavision.device.MentraGlassesDevice
import br.com.facilmova.peritavision.device.PhoneGlassesDevice
import br.com.facilmova.peritavision.domain.CofreCustodia
import br.com.facilmova.peritavision.domain.Evidencia
import br.com.facilmova.peritavision.domain.SelarCustodia
import br.com.facilmova.peritavision.domain.TipoEvidencia
import br.com.facilmova.peritavision.net.AudioStreamer
import br.com.facilmova.peritavision.net.PonteGemini
import br.com.facilmova.peritavision.data.ConfiguracoesApp
import br.com.facilmova.peritavision.ui.TelaConfiguracoes
import br.com.facilmova.peritavision.net.BackendClient
import br.com.facilmova.peritavision.ui.AvisoEscuta
import br.com.facilmova.peritavision.ui.BarraDeStatus
import br.com.facilmova.peritavision.ui.BarraDeTopo
import br.com.facilmova.peritavision.ui.BotaoContorno
import br.com.facilmova.peritavision.ui.BotaoPrimario
import br.com.facilmova.peritavision.ui.BotaoTonal
import br.com.facilmova.peritavision.ui.CabecalhoCartao
import br.com.facilmova.peritavision.ui.CampoPv
import br.com.facilmova.peritavision.ui.BalaoConversa
import br.com.facilmova.peritavision.ui.BarraProgresso
import br.com.facilmova.peritavision.ui.LinhaCampo
import br.com.facilmova.peritavision.ui.SecaoLaudoPv
import br.com.facilmova.peritavision.ui.CartaoPasso
import br.com.facilmova.peritavision.ui.CartaoPv
import br.com.facilmova.peritavision.ui.MolduraVisor
import br.com.facilmova.peritavision.ui.TituloSecao
import br.com.facilmova.peritavision.ui.Contador
import br.com.facilmova.peritavision.ui.Etiqueta
import br.com.facilmova.peritavision.ui.FaixaProntidao
import br.com.facilmova.peritavision.ui.LinhaDado
import br.com.facilmova.peritavision.ui.PeritavisionTheme
import br.com.facilmova.peritavision.ui.Prontidao
import br.com.facilmova.peritavision.ui.PvTheme
import br.com.facilmova.peritavision.ui.RodapeMarca
import br.com.facilmova.peritavision.ui.TextoApoio
import br.com.facilmova.peritavision.ui.Tom
import br.com.facilmova.peritavision.voice.VoiceTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Uma seção do laudo em preenchimento, já resolvida para a tela. */
internal data class SecaoLaudo(
    val numero: Int,
    val titulo: String,
    val preenchida: Boolean,
    val dica: String? = null,
    val campos: List<Pair<String, String>> = emptyList(),
    val itens: List<String> = emptyList(),
)

/** Laudo pericial em preenchimento — as 8 seções do modelo POLITEC-MT, com o que a sessão já sabe. */
@Composable
internal fun CartaoLaudoEmPreenchimento(
    protocolo: String,
    caso: BackendClient.CasoAtena?,
    ficha: BackendClient.FichaLacre?,
    fotosSeladas: Int,
    narracoes: List<String>,
    achados: List<String>,
) {
    val autoridade = caso?.autoridade ?: ficha?.solicitante
    val orgao = caso?.unidadeRequisitante ?: ficha?.unidadeRequisitante
    val dataOcorrencia = dataLegivel(caso?.dataOcorrencia ?: ficha?.dataOcorrencia)
    val vitima = ficha?.vitima
    val materiais = paraTela(caso?.materiais?.ifEmpty { null } ?: ficha?.materiais ?: emptyList())
    val objetivos = paraTela(
        caso?.exames?.ifEmpty { null }
            ?: caso?.naturezas?.ifEmpty { null }
            ?: ficha?.naturezas ?: emptyList(),
    )

    val historico = buildList {
        autoridade?.let { add("Autoridade" to it) }
        orgao?.let { add("Órgão solicitante" to it) }
        dataOcorrencia?.let { add("Data da ocorrência" to it) }
        vitima?.let { add("Vítima" to it) }
    }

    val consideracoes = narracaoParaOLaudo(narracoes)

    val secoes = listOf(
        SecaoLaudo(1, "Histórico", preenchida = historico.isNotEmpty(),
            dica = "Vem do ATENA ao abrir a perícia.", campos = historico),
        SecaoLaudo(2, "Materiais recebidos", preenchida = materiais.isNotEmpty(),
            dica = "Vem do ATENA ao abrir a perícia.", itens = materiais),
        SecaoLaudo(3, "Objetivos dos exames", preenchida = objetivos.isNotEmpty(),
            dica = "Vem do ATENA ao abrir a perícia.", itens = objetivos),
        SecaoLaudo(4, "Materiais e métodos", preenchida = false,
            dica = "Redigida na revisão do laudo, a partir do método padrão."),
        SecaoLaudo(5, "Resultados", preenchida = fotosSeladas > 0 || achados.isNotEmpty(),
            dica = "Os achados que você enunciar (\"item um, sangue negativo\") entram aqui; as fotos viram figuras.",
            campos = listOf("Figuras" to "$fotosSeladas foto(s) selada(s) por hash", "Achados" to "${achados.size} registrado(s)"),
            itens = achados.takeLast(5)),
        SecaoLaudo(6, "Considerações", preenchida = consideracoes.isNotEmpty(),
            dica = "O que você narrar na bancada entra aqui.",
            itens = consideracoes.takeLast(3).map { if (it.length > 180) it.take(180) + "…" else it }),
        SecaoLaudo(7, "Conclusão", preenchida = false,
            dica = "Só o perito escreve — no painel web, na revisão."),
        SecaoLaudo(8, "Disposições finais", preenchida = false,
            dica = "Texto padrão do modelo, incluído na geração."),
    )
    val preenchidas = secoes.count { it.preenchida }
    val pct = preenchidas * 100 / secoes.size

    CartaoPv {
        CabecalhoCartao(
            titulo = "Laudo pericial — em preenchimento",
            etiqueta = "$pct%",
            tomEtiqueta = if (pct >= 100) Tom.OK else Tom.ATENCAO,
            grande = true,
        )
        Text(
            "Protocolo ${protocolo.ifBlank { "—" }} · modelo POLITEC-MT",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(12.dp))
        BarraProgresso(pct / 100f)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            secoes.forEach { sec ->
                SecaoLaudoPv(
                    numero = sec.numero,
                    titulo = sec.titulo,
                    preenchida = sec.preenchida,
                    dica = sec.dica,
                ) {
                    sec.campos.forEach { (rotulo, valor) -> LinhaCampo(rotulo, valor) }
                    sec.itens.forEach { item ->
                        Text(
                            "• $item",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        TextoApoio("Ao finalizar, o servidor monta o laudo com tudo isso — revisão e assinatura ficam no painel.")
    }
}

@Composable
internal fun CartaoFichaLacre(
    ficha: BackendClient.FichaLacre,
    abrindo: Boolean,
    onAbrirPericia: () -> Unit,
    onLerOutro: () -> Unit,
) {
    CartaoPv {
        CabecalhoCartao(
            titulo = "Lacre lido pelos óculos",
            grande = true,
            etiqueta = ficha.codigo,
            tomEtiqueta = Tom.OK,
        )
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.background)
                .padding(13.dp),
        ) {
            Text(
                "Protocolo ${ficha.numeroProtocolo}",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (ficha.naturezas.isNotEmpty()) {
                Text(
                    ficha.naturezas.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            LinhaDado("Solicitante", ficha.solicitante ?: "a confirmar")
            LinhaDado("Unidade", ficha.unidadeRequisitante ?: "a confirmar")
            LinhaDado("Vítima", ficha.vitima ?: "não informada")
            LinhaDado("Data do fato", ficha.dataOcorrencia ?: "não informada")
            LinhaDado("Objetos", "${ficha.quantidadeMateriais}", ultima = ficha.materiais.isEmpty())
            if (ficha.materiais.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "MATERIAIS",
                    style = MaterialTheme.typography.labelSmall,
                    color = PvTheme.extras.textoSuave,
                )
                Spacer(Modifier.height(4.dp))
                ficha.materiais.forEach { m ->
                    Text(
                        "• $m",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        BotaoPrimario(
            texto = if (abrindo) "Abrindo..." else "Abrir perícia deste lacre",
            habilitado = !abrindo,
            onClick = onAbrirPericia,
        )
        Spacer(Modifier.height(8.dp))
        BotaoContorno(texto = "Ler outro lacre", onClick = onLerOutro)
    }
}

@Composable
internal fun CartaoAssistenteIa(
    ativo: Boolean,
    /** o circuito de vídeo está de pé (quadros chegando ao servidor) */
    enxergando: Boolean,
    olhandoAgora: Boolean,
    /** roteiro em uso ("Trilha A — Faca / perfurocortante"); null = indefinido */
    trilha: String?,
    /** a IA está na triagem, perguntando o tipo de exame ao perito */
    perguntandoTrilha: Boolean,
    /** modo da IA: conversa | silencio | pausa */
    modo: String,
    /** troca de modo pelo toque (reserva — o perito de luvas usa a voz) */
    onModo: (String) -> Unit,
    /** diagnóstico da saída de voz ("→ Mentra Live · 12 trecho(s)", "ERRO: ...") */
    voz: String,
    perito: String,
    resposta: String,
    onAlternar: () -> Unit,
) {
    CartaoPv {
        CabecalhoCartao(
            titulo = "Assistente de voz",
            // Estados de verdade, não dois: desligado / ativo (câmera em repouso, o normal) / olhando agora / sem imagem.
            etiqueta = when {
                !ativo -> "desligado"
                perguntandoTrilha -> "perguntando o exame"
                modo == "pausa" -> "gravação em pausa"
                modo == "silencio" -> "silêncio — ouvindo e registrando"
                olhandoAgora -> "olhando agora"
                else -> "em conversa"
            },
            tomEtiqueta = when {
                !ativo -> Tom.NEUTRO
                perguntandoTrilha -> Tom.ATENCAO
                modo == "pausa" -> Tom.ERRO
                modo == "silencio" -> Tom.NEUTRO
                else -> Tom.OK
            },
            grande = true,
        )
        if (!ativo) {
            TextoApoio(
                "Assistente de voz pelos óculos (Gemini). Liga sozinho quando a sessão " +
                    "abre; aqui você religa se tiver desligado. Uma cópia do áudio vai aos " +
                    "servidores do Google — use apenas em teste/bancada, não em caso real.",
            )
            Spacer(Modifier.height(12.dp))
            BotaoTonal(texto = "Ligar assistente", icone = R.drawable.ic_pv_mic, onClick = onAlternar)
            return@CartaoPv
        }
        when {
            perguntandoTrilha -> TextoApoio(
                "A ponte ainda está definindo o roteiro pelos materiais do caso…",
                Tom.ATENCAO,
            )
            trilha != null -> LinhaCampo("Roteiro", trilha)
        }
        if (voz.startsWith("ERRO")) {
            TextoApoio("Voz $voz", Tom.ERRO)
        }
        if (!enxergando) {
            TextoApoio(
                "Sem imagem dos óculos: o assistente ouve, mas NÃO está vendo a bancada — " +
                    "não confie no que ele disser sobre o material.",
                Tom.ATENCAO,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (perito.isNotBlank()) BalaoConversa(perito, doPerito = true)
        if (resposta.isNotBlank()) BalaoConversa(resposta, doPerito = false)
        if (perito.isBlank() && resposta.isBlank()) {
            TextoApoio(
                when (modo) {
                    "silencio" -> "Ouvindo e registrando achados em silêncio. \"O que foi salvo?\" ela lê. Diga a palavra de conversa para ela voltar a falar."
                    "pausa" -> "Gravação em pausa: o vídeo dos óculos está parado, nada do que for dito vai para o laudo e nenhum comando executa. Diga \"assistente\" ou \"silêncio\" para voltar."
                    else -> "Fale normalmente: ela responde, conduz o roteiro e registra os achados. Diga a palavra de silêncio para trabalhar sem interrupção."
                },
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (modo == "conversa") BotaoTonal("Conversar", modifier = Modifier.weight(1f), onClick = {})
            else BotaoContorno("Conversar", modifier = Modifier.weight(1f), onClick = { onModo("conversa") })
            if (modo == "silencio") BotaoTonal("Silêncio", modifier = Modifier.weight(1f), onClick = {})
            else BotaoContorno("Silêncio", modifier = Modifier.weight(1f), onClick = { onModo("silencio") })
            if (modo == "pausa") BotaoTonal("Pausa", modifier = Modifier.weight(1f), onClick = {})
            else BotaoContorno("Pausa", tom = Tom.ERRO, modifier = Modifier.weight(1f), onClick = { onModo("pausa") })
        }
        Spacer(Modifier.height(12.dp))
        BotaoContorno(
            texto = "Desligar assistente",
            icone = R.drawable.ic_pv_stop,
            tom = Tom.ERRO,
            onClick = onAlternar,
        )
    }
}
