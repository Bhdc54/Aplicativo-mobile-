package br.com.facilmova.peritavision.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import br.com.facilmova.peritavision.R

/*
 * MARCA PERITAVISION — desenhada por vetor, não por imagem.
 *
 * O arquivo original (CorelDRAW) traz "PERITA" em ciano e "VISION" em cinza
 * claro, pensado para fundo escuro. No tablet a tela é clara: o cinza #CCCCCC
 * sumiria. Então a marca é desenhada aqui com DUAS cores independentes — o
 * ciano da marca fica fixo e a outra metade acompanha o tema, legível no claro
 * e no escuro. Sem PNG: fica nítida em qualquer densidade de tela.
 *
 * Proporção original: 921,31 × 89,42 (≈10,3 : 1). A altura manda; a largura
 * sai dela.
 */
private const val LARGURA = 921.31f
private const val ALTURA = 89.42f
private const val PROPORCAO = LARGURA / ALTURA

/** O ciano da marca, igual ao do arquivo original. Não vem do tema: é a marca. */
val CianoPeritaVision = Color(0xFF10B6DC)

private const val D_PERITA =
    "M-0 0l68.26 0c15.57,0 28.32,12.74 28.32,28.31l0 0c0,15.57 -12.74,28.31 -28.32,28.31l-51.53 0 0 " +
    "30.33 -16.73 0 0 -30.33 0 -15.68 16.73 0 52.16 0c6.65,0 12.09,-5.44 12.09,-12.09l0 0c0,-6.65 " +
    "-5.44,-12.09 -12.09,-12.09l-68.89 0 0 -16.77zm106.1 16.73l0 -16.73 78.78 0 0 16.73 -78.78 0zm0 " +
    "35.12l0 -16.73 78.78 0 0 16.73 -78.78 0zm0 35.11l0 -16.73 78.78 0 0 16.73 -78.78 0zm89.37 " +
    "-86.96l59.83 0c15.57,0 28.32,12.74 28.32,28.31l0 0c0,15.57 -12.74,28.31 -28.32,28.31l-9.1 0 " +
    "30.33 30.33 -23.66 0 -31.83 -31.83 0 0 -14.18 -14.19 49.08 0c6.65,0 12.09,-5.44 12.09,-12.09l0 " +
    "0c0,-6.65 -5.44,-12.09 -12.09,-12.09l-60.46 0 0 -16.77zm99.07 0l16.73 0 0 86.96 -16.73 0 0 " +
    "-86.96zm25.09 16.73l0 -16.73 31.45 0 16.73 0 31.46 0 0 16.73 -31.46 0 0 70.23 -16.73 0 0 -70.23 " +
    "-31.45 0zm113.15 -16.73l50.29 86.77 -19.21 0.19 -31.07 -53.6 -31.07 53.6 -19.21 -0.19 50.29 " +
    "-86.77zm0 65.8l12.26 21.16 -24.52 0 12.26 -21.16z " +
    "M879.14,0L921.31,0L921.31,42.17L921.31,42.17L921.31,42.17Z"

private const val D_VISION =
    "M654.7 0l27.83 0 23.08 0 0 16.73 -23.08 0 -27.83 0 -7.71 0c-5.06,0 -9.2,4.14 -9.2,9.19 0,5.06 " +
    "4.14,9.2 9.2,9.2l2.41 0 33.13 0 0.51 0c14.26,0 25.93,11.67 25.93,25.93 0,14.26 -11.67,25.93 " +
    "-25.93,25.93l-5.81 0 -27.84 0 -23.08 0 0 -16.73 23.08 0 27.84 0 7.71 0c5.06,0 9.19,-4.14 " +
    "9.19,-9.2 0,-5.06 -4.14,-9.19 -9.19,-9.19l-2.42 0 -33.13 0 -0.51 0c-14.26,0 -25.93,-11.67 " +
    "-25.93,-25.93 0,-14.26 11.67,-25.93 25.93,-25.93l5.81 0z M791.09 0c24.01,0 43.48,19.47 " +
    "43.48,43.48 0,24.01 -19.47,43.48 -43.48,43.48 -24.01,0 -43.48,-19.47 -43.48,-43.48 0,-24.01 " +
    "19.47,-43.48 43.48,-43.48zm0 16.73c14.77,0 26.75,11.98 26.75,26.75 0,14.77 -11.98,26.75 " +
    "-26.75,26.75 -14.77,0 -26.75,-11.98 -26.75,-26.75 0,-14.77 11.98,-26.75 26.75,-26.75z " +
    "M538.43,87.44L588.72,0.67L569.51,0.47L538.44,54.07L507.36,0.47L488.15,0.67Z " +
    "M594.79,0L611.52,0L611.52,86.96L594.79,86.96Z M719.56,0L736.29,0L736.29,86.96L719.56,86.96Z " +
    "M848.17,0L853.2,0L864.77,11.25L921.31,66.08L921.31,89.42L864.9,34.7L864.9,86.96L848.17,86.96Z"

/**
 * A marca PeritaVision. [altura] manda na largura (a proporção é fixa).
 * [corVision] só precisa ser informada quando o fundo não for o da superfície
 * do tema — por padrão ela segue o texto da tela.
 */
@Composable
fun MarcaPeritaVision(
    modifier: Modifier = Modifier,
    altura: Dp = 18.dp,
    corPerita: Color = CianoPeritaVision,
    corVision: Color = MaterialTheme.colorScheme.onSurface,
) {
    val perita = remember { PathParser().parsePathString(D_PERITA).toPath() }
    val vision = remember { PathParser().parsePathString(D_VISION).toPath() }
    Canvas(modifier.size(width = altura * PROPORCAO, height = altura)) {
        val k = size.height / ALTURA
        scale(scaleX = k, scaleY = k, pivot = Offset.Zero) {
            drawPath(perita, corPerita, style = Fill)
            drawPath(vision, corVision, style = Fill)
        }
    }
}

/**
 * ASSINATURA DO RODAPÉ — a marca PeritaVision e, abaixo, quem desenvolveu.
 *
 * A logo da Facilmova estava na barra de topo, disputando espaço com o brasão
 * da POLITEC, o nome do produto e a engrenagem. Desceu para cá (12/09/2026):
 * o topo fica do cliente, o rodapé assina o fornecedor — que é onde se procura
 * por essa informação, e não atrapalha quem está trabalhando.
 *
 * A logo da Facilmova é um PNG colorido feito para fundo claro, então continua
 * numa pílula branca: no tema escuro ela sumiria sobre o fundo da tela.
 */
@Composable
fun RodapeAssinatura(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(top = 22.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MarcaPeritaVision(altura = 17.dp, corVision = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "desenvolvido por".uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = PvTheme.extras.textoSuave,
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color.White)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(7.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Image(
                    painter = painterResource(id = R.drawable.logo_facilmova),
                    contentDescription = "Facilmova",
                    modifier = Modifier.height(16.dp),
                )
            }
        }
    }
}
