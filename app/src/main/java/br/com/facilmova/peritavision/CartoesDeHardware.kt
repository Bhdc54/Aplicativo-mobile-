// Cartões do HARDWARE da bancada: os óculos, a Wi-Fi deles, o servidor
// de vídeo e o visor com a imagem ao vivo.
//
// Saíram do MainActivity.kt em 08/09/2026, quando ele passou de 3.100
// linhas. Ficam no MESMO pacote e na mesma pasta de propósito: assim a
// separação não exigiu mexer em import nenhum do projeto, e o risco de
// uma mudança grande e não compilada aqui ficou perto de zero.
//
// `internal` e não `private` porque em Kotlin `private` vale só dentro do
// arquivo, e quem chama estes cartões é o CaptureScreen, que ficou lá.
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

@Composable
internal fun CartaoOculos(
    numero: Int,
    destaque: Boolean,
    ehMentra: Boolean,
    conectado: Boolean,
    conectando: Boolean,
    temPermissoes: Boolean,
    onConectar: () -> Unit,
) {
    if (!ehMentra) {
        // Modo de teste: a "muleta" é a câmera do celular. Deixar isso explícito
        // na tela evita a pior confusão possível numa demonstração — achar que
        // está usando os óculos quando não está.
        CartaoPasso(
            numero = numero,
            titulo = "Câmera do celular",
            etiqueta = if (temPermissoes) "pronto" else "sem permissão",
            tomEtiqueta = if (temPermissoes) Tom.OK else Tom.ERRO,
            ativo = !temPermissoes,
            bloqueado = false,
            concluido = temPermissoes,
            resumoConcluido = "Modo de teste — a foto é tirada pelo celular",
        ) {
            TextoApoio(
                "TIPO_DISPOSITIVO está em PHONE. A foto é tirada pelo celular e " +
                    "selada localmente — os óculos não participam."
            )
        }
        return
    }

    CartaoPasso(
        numero = numero,
        titulo = "Óculos Mentra Live",
        etiqueta = when {
            conectado -> "conectado"
            conectando -> "procurando"
            else -> "desconectado"
        },
        tomEtiqueta = when {
            conectado -> Tom.OK
            conectando -> Tom.ATENCAO
            else -> Tom.ERRO
        },
        ativo = destaque,
        bloqueado = false,
        concluido = conectado,
        resumoConcluido = "Conectado por Bluetooth",
    ) {
        TextoApoio(
            when {
                conectado -> "O Bluetooth leva os comandos e o áudio do microfone. A foto sobe pela Wi-Fi dos óculos."
                conectando -> "Procurando os óculos por Bluetooth..."
                else -> "Ligue os óculos, deixe-os por perto e toque em conectar. É preciso Bluetooth e Localização ativos."
            }
        )
        Spacer(Modifier.height(12.dp))
        BotaoPrimario(
            texto = when {
                conectado -> "Óculos conectado"
                conectando -> "Procurando óculos..."
                else -> "Conectar óculos"
            },
            icone = if (conectado) R.drawable.ic_pv_check else R.drawable.ic_pv_bluetooth,
            habilitado = !conectado && !conectando,
            onClick = onConectar,
        )
    }
}

@Composable
internal fun CartaoWifiOculos(
    numero: Int,
    destaque: Boolean,
    conectado: Boolean,
    wifiOculos: Boolean,
    ssidOculos: String?,
    ssid: String,
    onSsid: (String) -> Unit,
    senha: String,
    onSenha: (String) -> Unit,
    onEnviar: () -> Unit,
) {
    CartaoPasso(
        numero = numero,
        titulo = "Wi-Fi do local",
        etiqueta = when {
            wifiOculos -> "conectado"
            conectado -> "pendente"
            else -> "aguardando"
        },
        tomEtiqueta = when {
            wifiOculos -> Tom.OK
            conectado -> Tom.ERRO
            else -> Tom.NEUTRO
        },
        ativo = destaque,
        bloqueado = !conectado,
        concluido = wifiOculos,
        resumoConcluido = "Rede ${ssidOculos ?: "—"}",
    ) {
        TextoApoio(
            if (wifiOculos) {
                "É por esta rede que o JPEG sobe ao servidor."
            } else {
                "As fotos e o vídeo sobem pelo Wi-Fi DOS ÓCULOS — rede 2,4 GHz com internet. " +
                    "A rede fica salva no tablet e é enviada sozinha toda vez que os óculos conectam."
            }
        )
        Spacer(Modifier.height(10.dp))
        CampoPv(
            valor = ssid,
            onValueChange = onSsid,
            rotulo = "Nome da rede (SSID)",
            habilitado = conectado,
        )
        Spacer(Modifier.height(9.dp))
        CampoPv(
            valor = senha,
            onValueChange = onSenha,
            rotulo = "Senha da rede",
            habilitado = conectado,
            segredo = true,
        )
        Spacer(Modifier.height(12.dp))
        BotaoTonal(
            texto = "Enviar Wi-Fi aos óculos",
            icone = R.drawable.ic_pv_wifi,
            habilitado = conectado,
            onClick = onEnviar,
        )
    }
}

@Composable
internal fun CartaoServidor(
    numero: Int,
    bloqueado: Boolean,
    destaque: Boolean,
    onLerLacre: () -> Unit,
    matricula: String,
    onMatricula: (String) -> Unit,
    protocolo: String,
    onProtocolo: (String) -> Unit,
    editavel: Boolean,
    temSessao: Boolean,
    ocupado: Boolean,
    fotosEnviadas: Int,
    temLaudo: Boolean,
    onIniciar: () -> Unit,
    onFinalizar: () -> Unit,
) {
    // Sessão aberta fora do passo de destaque: o Finalizar mora no cartão de
    // captura — este cartão simplesmente sai da frente.
    if (temSessao && !destaque) return

    CartaoPasso(
        numero = numero,
        titulo = "Perícia",
        etiqueta = if (temSessao) "sessão aberta" else "ATENA",
        tomEtiqueta = if (temSessao) Tom.OK else Tom.NEUTRO,
        ativo = destaque,
        bloqueado = bloqueado && !temSessao,
        concluido = temSessao,
        resumoConcluido = "Protocolo ${protocolo.trim()}",
    ) {
        TextoApoio(
            if (temSessao) {
                "Sessão aberta. Finalizar manda o backend montar o laudo."
            } else {
                "Informe o protocolo — os dados da perícia vêm do ATENA."
            }
        )
        if (bloqueado && !temSessao) {
            Spacer(Modifier.height(4.dp))
            TextoApoio("Óculos desconectados — conecte pela engrenagem, ao lado da logo.", Tom.ATENCAO)
        }
        Spacer(Modifier.height(10.dp))
        if (!temSessao) {
            // QUEM ESTÁ NA BANCADA. Não é login — é identificação: o tablet é
            // compartilhado e entra com a credencial dele (local.properties),
            // e esta matrícula diz de quem é a perícia. É ela que separa os
            // laudos por perito no painel. A senha existe onde importa: no
            // painel web, para revisar e validar o laudo.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CampoPv(
                    valor = matricula,
                    onValueChange = onMatricula,
                    rotulo = "Matrícula",
                    habilitado = editavel && !bloqueado,
                    modifier = Modifier.weight(0.7f),
                )
                CampoPv(
                    valor = protocolo,
                    onValueChange = onProtocolo,
                    rotulo = "Protocolo",
                    habilitado = editavel && !bloqueado,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            BotaoPrimario(
                texto = if (ocupado) "Sincronizando com o ATENA..." else "Iniciar perícia",
                icone = R.drawable.ic_pv_play,
                habilitado = !ocupado && !bloqueado && protocolo.isNotBlank(),
                onClick = onIniciar,
            )
            Spacer(Modifier.height(8.dp))
            BotaoContorno(
                texto = "Ler lacre (QR do envelope)",
                icone = R.drawable.ic_pv_camera,
                habilitado = !ocupado && !bloqueado,
                onClick = onLerLacre,
            )
        } else {
            BotaoContorno(
                texto = if (ocupado) "Finalizando..." else "Finalizar sessão e gerar laudo",
                icone = R.drawable.ic_pv_custodia,
                habilitado = !ocupado,
                tom = Tom.ERRO,
                onClick = onFinalizar,
            )
        }
        if (temSessao) {
            Spacer(Modifier.height(4.dp))
            Contador(fotosEnviadas, "fotos enviadas ao backend")
        }
        if (temLaudo) {
            Spacer(Modifier.height(10.dp))
            TextoApoio("Laudo pronto — revise e baixe no site do PeritaVision.", Tom.OK)
        }
    }
}

/**
 * A janela onde o decodificador desenha. Um SurfaceView cru, não um player: o
 * MediaCodec escreve direto na Surface, sem passar bitmap por Kotlin.
 *
 * A Surface nasce e morre com a view (o Android a destrói ao apagar a tela ou
 * ao rolar a lista para longe), e o decodificador precisa saber das duas
 * coisas — sem o surfaceDestroyed ele continuaria entregando quadro a uma
 * superfície morta e o codec cairia em IllegalStateException.
 */
@Composable
internal fun VisorAoVivoDosOculos(
    decodificador: br.com.facilmova.peritavision.rtmp.DecodificadorDeVideo,
    /** Recebe a SurfaceView quando ela nasce e null quando sai da tela: é dela
     *  que o app recorta o quadro (PixelCopy) quando os óculos recusam a foto. */
    aoTerView: (android.view.SurfaceView?) -> Unit = {},
) {
    DisposableEffect(Unit) { onDispose { aoTerView(null) } }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            android.view.SurfaceView(ctx).also(aoTerView).apply {
                holder.addCallback(object : android.view.SurfaceHolder.Callback {
                    override fun surfaceCreated(h: android.view.SurfaceHolder) {
                        decodificador.definirSuperficie(h.surface)
                    }
                    override fun surfaceChanged(h: android.view.SurfaceHolder, formato: Int, largura: Int, altura: Int) {
                        decodificador.definirSuperficie(h.surface)
                    }
                    override fun surfaceDestroyed(h: android.view.SurfaceHolder) {
                        decodificador.definirSuperficie(null)
                    }
                })
            }
        },
    )
}

@Composable
internal fun CartaoVisaoOculos(
    urlFlv: String?,
    aoVivo: Boolean,
    protocolo: String,
    /** A imagem ao vivo (SurfaceView do decodificador); null no modo servidor. */
    imagem: (@Composable () -> Unit)? = null,
    imagemEstado: br.com.facilmova.peritavision.rtmp.DecodificadorDeVideo.Estado? = null,
    /** Estado do receptor local (modo "tablet"); null no modo servidor. */
    receptor: br.com.facilmova.peritavision.rtmp.ReceptorDeVideo.Estado? = null,
    perfil: String = "",
    subindo: Int = 0,
    subidos: Int = 0,
    comFalha: Int = 0,
) {
    if (receptor != null) {
        // MODO TABLET: sem player (ainda) — o que importa no teste de campo é
        // saber que os quadros estão chegando, a que taxa e com que tamanho.
        val recebendo = receptor.publicando && System.currentTimeMillis() - receptor.ultimoQuadroMs < 4_000
        var segundos by remember(receptor.chave) { mutableIntStateOf(0) }
        LaunchedEffect(receptor.chave, receptor.publicando) {
            while (receptor.publicando) { delay(1000); segundos++ }
        }
        val cronometro = "%02d:%02d".format(segundos / 60, segundos % 60)
        val mb = receptor.bytes / 1_000_000.0
        val legenda = when {
            !receptor.ligado -> receptor.erro ?: "Receptor do tablet desligado."
            receptor.erro != null -> receptor.erro
            receptor.publicando && !recebendo -> "Óculos conectados ao tablet, mas sem quadro há mais de 4 s."
            receptor.publicando -> "Recebendo direto dos óculos pela Wi-Fi da bancada — sem internet no caminho."
            // O diagnóstico de rede (endereço, porta, handshake) foi para o log
            // em 12/09/2026: na tela ele não ajuda o perito e assusta quem está
            // assistindo. Aqui fica o que dá para FAZER a respeito.
            receptor.ultimoCliente != null ->
                "Os óculos acharam o tablet, mas ainda não começaram a transmitir. Aguarde alguns segundos."
            else -> "Aguardando os óculos transmitirem. Confira se estão na mesma Wi-Fi da bancada."
        }
        // Com a IMAGEM na tela, nada escrito por cima dela: nem quadros por
        // segundo, nem MB, nem segmento, nem fonte, nem protocolo — só a
        // pílula AO VIVO e o cronômetro (09/09/2026: "tem como tirar essas
        // escritas do vídeo?"). Os números continuam existindo, mas só
        // aparecem quando não há imagem, que é quando servem de diagnóstico.
        // `imagemEstado != null &&` explícito: com `imagemEstado?.x == true`
        // o Kotlin não faz smart cast e o acesso seguinte não compila.
        val comImagem = imagem != null && imagemEstado != null &&
            imagemEstado.decodificando && (imagemEstado.quadrosNaTela > 0 || recebendo)
        val envio = buildString {
            if (subindo > 0) append("subindo $subindo · ")
            if (subidos > 0) append("no servidor $subidos · ")
            if (comFalha > 0) append("FALHOU $comFalha · ")
        }.trimEnd(' ', '·')
        // Com imagem boa a legenda cala; ela volta para dizer o que está errado.
        val legendaComImagem = when {
            comFalha > 0 -> "Vídeo: $envio."
            imagemEstado != null && imagemEstado.erro != null -> "Imagem: ${imagemEstado.erro}"
            !recebendo -> legenda
            else -> ""
        }
        MolduraVisor(
            aoVivo = recebendo,
            cronometro = cronometro,
            fonte = "TABLET · $perfil",
            protocolo = if (protocolo.isBlank()) "" else "PROT $protocolo",
            legenda = if (comImagem) legendaComImagem else legenda,
            imagemLimpa = comImagem,
            conteudo = {
                if (imagem != null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) { imagem() }
                    }
                }
                if (!comImagem) Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = if (receptor.publicando) "%.1f".format(receptor.quadrosPorSegundo) else "—",
                        style = MaterialTheme.typography.displayMedium,
                        color = if (recebendo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("quadros por segundo", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "${receptor.quadros} quadros · %.1f MB · segmento ${receptor.segmentosFechados + if (receptor.publicando) 1 else 0}".format(mb),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    imagemEstado?.erro?.let {
                        Text("imagem: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (envio.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(envio, style = MaterialTheme.typography.labelMedium,
                            color = if (comFalha > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    receptor.ultimoMotivo?.let {
                        Spacer(Modifier.height(4.dp))
                        Text("último segmento: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
        )
        return
    }
    val transmitindo = aoVivo && urlFlv != null

    // Cronômetro da transmissão: zera quando a URL muda (nova sessão).
    var segundos by remember(urlFlv) { mutableIntStateOf(0) }
    LaunchedEffect(urlFlv, transmitindo) {
        while (transmitindo) {
            delay(1000)
            segundos++
        }
    }
    val cronometro = "%02d:%02d".format(segundos / 60, segundos % 60)

    val player: (@Composable () -> Unit)? = if (!transmitindo) null else {
        {
            val context = LocalContext.current
            // Player recriado quando a URL muda (nova sessão) e liberado quando o
            // cartão sai de cena — sem isso o decodificador fica preso em segundo plano.
            val exo = remember(urlFlv) {
                androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
                    setMediaItem(androidx.media3.common.MediaItem.fromUri(urlFlv!!))
                    // O monitor é só visual: com o áudio ligado, o tablet toca a
                    // voz do perito de volta (eco) e alimenta o loop em que a IA
                    // se ouve. Vídeo segue normal; áudio fica mudo.
                    volume = 0f
                    prepare()
                    playWhenReady = true
                }
            }
            DisposableEffect(exo) { onDispose { exo.release() } }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    androidx.media3.ui.PlayerView(ctx).apply {
                        useController = false
                        this.player = exo
                    }
                },
                update = { view -> view.player = exo },
            )
        }
    }

    MolduraVisor(
        aoVivo = transmitindo,
        cronometro = cronometro,
        fonte = "MENTRA LIVE · 1080p",
        protocolo = if (protocolo.isBlank()) "" else "PROT $protocolo",
        legenda = if (transmitindo) {
            "Atraso de alguns segundos é normal — o vídeo passa pelo servidor."
        } else {
            "O vídeo dos óculos aparece aqui, ao vivo, assim que a transmissão começar."
        },
        conteudo = player,
    )
}
