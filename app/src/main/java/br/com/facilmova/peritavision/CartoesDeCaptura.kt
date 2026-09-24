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

@Composable
internal fun CartaoCaptura(
    destaque: Boolean,
    podeCapturar: Boolean,
    motivoBloqueio: String?,
    fotosEnviadas: Int,
    /** Capturas que ficaram só como marca no vídeo — ver quadrosMarcados. */
    quadrosMarcados: Int = 0,
    /** Fotos completas paradas no tablet, esperando o servidor aceitar. */
    fotosParadasNoTablet: Int = 0,
    /** Por onde a foto está indo, em uma frase — o diagnóstico da bancada. */
    rotaDaFoto: String? = null,
    vozAtiva: Boolean,
    ouvindoPelosOculos: Boolean,
    assistenteIa: Boolean,
    gravandoAudio: Boolean,
    previewCamera: (@Composable () -> Unit)?,
    onFoto: () -> Unit,
    onVoz: () -> Unit,
    onAudio: () -> Unit,
    onFinalizar: (() -> Unit)? = null,
    finalizando: Boolean = false,
) {
    CartaoPv(destaque = destaque) {
        CabecalhoCartao(
            titulo = "Captura de evidência",
            etiqueta = if (podeCapturar) "liberada" else "bloqueada",
            tomEtiqueta = if (podeCapturar) Tom.OK else Tom.NEUTRO,
            grande = true,
        )
        if (rotaDaFoto != null) {
            TextoApoio(rotaDaFoto, if (rotaDaFoto.startsWith("A foto chega")) Tom.OK else Tom.NEUTRO)
            Spacer(Modifier.height(6.dp))
        }
        if (motivoBloqueio != null) {
            TextoApoio(motivoBloqueio)
        } else if (fotosEnviadas > 0 || quadrosMarcados > 0 || fotosParadasNoTablet > 0) {
            if (fotosEnviadas > 0) Contador(fotosEnviadas, "fotos enviadas\ne seladas por hash")
            if (fotosParadasNoTablet > 0) {
                if (fotosEnviadas > 0) Spacer(Modifier.height(8.dp))
                TextoApoio(
                    "$fotosParadasNoTablet foto(s) estao NO TABLET e o servidor ainda nao aceitou. " +
                        "Tento a cada 30 s e de novo ao finalizar; se sobrar, o app avisa por voz.",
                    Tom.ATENCAO,
                )
            }
            if (quadrosMarcados > 0) {
                if (fotosEnviadas > 0 || fotosParadasNoTablet > 0) Spacer(Modifier.height(8.dp))
                TextoApoio(
                    "$quadrosMarcados captura(s) NÃO viraram foto: os óculos recusaram e ficou só a " +
                        "marca no vídeo. O servidor tenta recortar o quadro ao finalizar — se não " +
                        "conseguir, essas imagens não entram no laudo. Confira no painel antes de assinar.",
                    Tom.ATENCAO,
                )
            }
        } else {
            TextoApoio(
                if (assistenteIa) {
                    "Toque no botão, use a haste dos óculos, ou peça ao assistente " +
                        "com suas palavras (\"registra uma foto disso\")."
                } else {
                    "Toque no botão, use a haste dos óculos, ou fale \"capturar\"."
                },
            )
        }

        if (previewCamera != null) {
            Spacer(Modifier.height(12.dp))
            previewCamera()
        }

        Spacer(Modifier.height(14.dp))
        // O maior alvo da tela: é o botão que o dedo procura com a luva.
        BotaoPrimario(
            texto = "Tirar foto",
            icone = R.drawable.ic_pv_camera,
            habilitado = podeCapturar,
            grande = true,
            onClick = onFoto,
        )

        if (assistenteIa && podeCapturar) {
            AvisoEscuta(
                titulo = "Assistente ouvindo",
                detalhe = "fale normalmente — ele captura e encerra a pedido",
            )
        } else if (vozAtiva && podeCapturar) {
            AvisoEscuta(
                titulo = if (ouvindoPelosOculos) "Óculos ouvindo" else "Celular ouvindo",
                detalhe = "diga \"capturar\" ou \"finalizar\"",
                acao = "Parar voz",
                onAcao = onVoz,
            )
        }

        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            if (!assistenteIa && !vozAtiva) {
                BotaoContorno(
                    texto = "Comando de voz",
                    icone = R.drawable.ic_pv_mic,
                    habilitado = podeCapturar,
                    modifier = Modifier.weight(1f),
                    onClick = onVoz,
                )
            }
            BotaoContorno(
                texto = if (gravandoAudio) "Parar narração" else "Narração",
                icone = if (gravandoAudio) R.drawable.ic_pv_stop else R.drawable.ic_pv_mic,
                habilitado = podeCapturar,
                tom = if (gravandoAudio) Tom.ERRO else Tom.NEUTRO,
                modifier = Modifier.weight(1f),
                onClick = onAudio,
            )
        }
        if (onFinalizar != null) {
            Spacer(Modifier.height(12.dp))
            BotaoContorno(
                texto = if (finalizando) "Finalizando..." else "Finalizar sessão e gerar laudo",
                icone = R.drawable.ic_pv_custodia,
                habilitado = !finalizando,
                tom = Tom.ERRO,
                onClick = onFinalizar,
            )
        }
    }
}

/** Uma foto da perícia como a tela precisa dela: id, miniatura e de onde veio. */
internal data class FotoNaTela(
    val id: String,
    val miniatura: android.graphics.Bitmap?,
    /** true = não é foto dos óculos, é quadro recortado do vídeo (720p). */
    val doVideo: Boolean,
    val kb: Int,
    /** requestId da captura: casa a foto que o TABLET já mostrou com a mesma
     *  foto vinda do servidor, para não aparecer duas vezes na galeria. */
    val requestId: String? = null,
)

/** Decodifica o JPEG já REDUZIDO. Sem isto, meia dúzia de fotos de 3 MB em
 *  resolução cheia derrubaria o app por memória no meio da perícia. */
internal fun miniaturaDe(bytes: ByteArray, alvoPx: Int = 480): android.graphics.Bitmap? {
    val medir = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, medir)
    var escala = 1
    val maior = maxOf(medir.outWidth, medir.outHeight)
    // maior <= 0 = decode de medição falhou; alvoPx <= 0 nunca acontece, mas o laço não pode depender disso para terminar.
    if (maior > 0 && alvoPx > 0) while (maior / (escala * 2) >= alvoPx) escala *= 2
    val opcoes = android.graphics.BitmapFactory.Options().apply {
        inSampleSize = escala
        // JPEG não tem canal alfa: RGB_565 gasta metade da memória de ARGB_8888.
        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
    }
    return runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opcoes) }.getOrNull()
}

/** FOTOS DA PERÍCIA — miniaturas do que o SERVIDOR já recebeu, embaixo do vídeo. */
@Composable
internal fun CartaoFotosDaPericia(
    fotos: List<FotoNaTela>,
    /** Quantas o app contou e ainda não voltaram do servidor. */
    esperando: Int,
    onAmpliar: (FotoNaTela) -> Unit,
) {
    if (fotos.isEmpty() && esperando <= 0) return
    CartaoPv {
        CabecalhoCartao(
            titulo = "Fotos da perícia",
            etiqueta = if (fotos.isEmpty()) "subindo…" else "${fotos.size} no servidor",
            tomEtiqueta = if (fotos.isEmpty()) Tom.ATENCAO else Tom.OK,
            grande = true,
        )
        TextoApoio("Confira agora se saiu boa — foco, luz e enquadramento. Toque para ampliar.")
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            fotos.forEachIndexed { i, f ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(width = 132.dp, height = 100.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(Color(0xFF0E141C))
                            .clickable { onAmpliar(f) },
                        contentAlignment = Alignment.Center,
                    ) {
                        val bmp = f.miniatura
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "foto ${i + 1}",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Text("…", color = Color.White, style = MaterialTheme.typography.titleLarge)
                        }
                        if (f.doVideo) {
                            Text(
                                "DO VÍDEO",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .background(MaterialTheme.colorScheme.error)
                                    .padding(horizontal = 4.dp),
                            )
                        }
                    }
                    Text(
                        "${i + 1}${if (f.kb > 0) " · ${f.kb} kB" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (esperando > 0) {
                Box(
                    modifier = Modifier
                        .size(width = 132.dp, height = 100.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(Color(0xFF1A2430)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "subindo\n$esperando",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                }
            }
        }
        if (fotos.any { it.doVideo }) {
            Spacer(Modifier.height(8.dp))
            TextoApoio(
                "As marcadas DO VÍDEO não são foto dos óculos: são quadros recortados da " +
                    "gravação, em resolução menor. Se alguma for importante, refaça a foto.",
                Tom.ATENCAO,
            )
        }
    }
}

/** A foto em tela cheia, para julgar foco e luz de verdade. Toque fecha. */
@Composable
internal fun FotoAmpliada(
    foto: FotoNaTela,
    /** Resolução alta, quando já baixou; até lá mostra a miniatura. */
    emAlta: android.graphics.Bitmap?,
    onFechar: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xEE000000))
            .clickable(onClick = onFechar),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = emAlta ?: foto.miniatura
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "foto ampliada",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
        Text(
            when {
                emAlta == null -> "carregando em resolução cheia…"
                foto.doVideo -> "quadro do vídeo · toque para fechar"
                else -> "toque para fechar"
            },
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
        )
    }
}

@Composable
internal fun CartaoEvidencia(ev: Evidencia) {
    CartaoPv {
        CabecalhoCartao(
            titulo = "Última evidência",
            grande = true,
            etiqueta = "selada",
            tomEtiqueta = Tom.OK,
        )
        Spacer(Modifier.height(6.dp))
        LinhaDado("Tipo", ev.tipo.name)
        LinhaDado("Arquivo", ev.caminhoArquivo.substringAfterLast('/'))
        LinhaDado("SHA-256", ev.sha256.take(20) + "…", mono = true)
        LinhaDado("UTC", ev.timestampUtc, mono = true)
        LinhaDado("Caso", ev.casoId ?: "sem caso vinculado", ultima = true)
    }
}
