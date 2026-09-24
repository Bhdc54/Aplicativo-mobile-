// Cartão da perícia assistida remota (lado do campo) e a marcação da perita sobre o visor.
package br.com.facilmova.peritavision

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.facilmova.peritavision.ui.BotaoContorno
import br.com.facilmova.peritavision.ui.BotaoPrimario
import br.com.facilmova.peritavision.ui.BotaoTonal
import br.com.facilmova.peritavision.ui.CabecalhoCartao
import br.com.facilmova.peritavision.ui.CartaoPv
import br.com.facilmova.peritavision.ui.TextoApoio
import br.com.facilmova.peritavision.ui.Tom

internal data class InstrucaoAssistida(val id: String, val texto: String)

@Composable
internal fun CartaoPericiaAssistida(
    ativa: Boolean,
    codigo: String?,
    peritaOnline: Boolean,
    instrucao: InstrucaoAssistida?,
    mudo: Boolean,
    status: String?,
    ocupado: Boolean,
    onChamar: () -> Unit,
    onFeito: () -> Unit,
    onNaoConsigo: () -> Unit,
    onSilenciar: () -> Unit,
    onEncerrar: () -> Unit,
) {
    CartaoPv(destaque = ativa) {
        CabecalhoCartao(
            titulo = "Perícia assistida",
            etiqueta = when {
                !ativa -> null
                peritaOnline -> "perita em linha"
                else -> "aguardando a perita"
            },
            tomEtiqueta = if (peritaOnline) Tom.OK else Tom.ATENCAO,
            grande = true,
        )
        if (!ativa) {
            TextoApoio(
                "Uma perita oficial pode conduzir esta perícia de longe: a voz dela sai nos óculos e ela vê o que você vê. " +
                    "O assistente de IA fica desligado enquanto ela estiver em linha.",
            )
            Spacer(Modifier.height(12.dp))
            BotaoTonal(
                texto = "Chamar perita oficial",
                habilitado = !ocupado,
                onClick = onChamar,
            )
            return@CartaoPv
        }

        if (!peritaOnline && codigo != null) {
            Spacer(Modifier.height(6.dp))
            TextoApoio("Passe este código à perita. Vale por 10 minutos e para um único acesso.")
            Spacer(Modifier.height(8.dp))
            Text(
                codigo.chunked(3).joinToString(" "),
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 48.sp, letterSpacing = 4.sp),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (peritaOnline) {
            Spacer(Modifier.height(6.dp))
            TextoApoio(
                "Você está em linha com a perita. A voz dela sai nos óculos; enquanto ela fala, seu microfone fica mudo.",
                Tom.OK,
            )
        }

        if (instrucao != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                "INSTRUÇÃO DA PERITA",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                instrucao.texto,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BotaoPrimario(
                    texto = "Feito",
                    icone = R.drawable.ic_pv_check,
                    grande = true,
                    modifier = Modifier.weight(2f),
                    onClick = onFeito,
                )
                Spacer(Modifier.width(10.dp))
                BotaoContorno(
                    texto = "Não consigo",
                    modifier = Modifier.weight(1f),
                    onClick = onNaoConsigo,
                )
            }
        }

        status?.let {
            Spacer(Modifier.height(8.dp))
            TextoApoio(it)
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            BotaoContorno(
                texto = if (mudo) "Reabrir microfone" else "Silenciar microfone",
                modifier = Modifier.weight(1f),
                onClick = onSilenciar,
            )
            Spacer(Modifier.width(10.dp))
            BotaoContorno(
                texto = "Encerrar sala",
                tom = Tom.ERRO,
                modifier = Modifier.weight(1f),
                onClick = onEncerrar,
            )
        }
    }
}

/** O visor ao vivo com o ponto que a perita marcou (círculo tracejado), quando houver. */
@Composable
internal fun VisorComMarcacao(
    decodificador: br.com.facilmova.peritavision.rtmp.DecodificadorDeVideo,
    marcacao: Pair<Float, Float>?,
    aoTerView: (android.view.SurfaceView?) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        VisorAoVivoDosOculos(decodificador, aoTerView)
        if (marcacao != null) {
            Canvas(Modifier.fillMaxSize()) {
                val centro = Offset(size.width * marcacao.first, size.height * marcacao.second)
                drawCircle(
                    color = Color(0xFFE0A143),
                    radius = size.minDimension * 0.09f,
                    center = centro,
                    style = Stroke(width = 6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 14f))),
                )
                drawCircle(color = Color(0xFFE0A143), radius = 6f, center = centro)
            }
        }
    }
}
