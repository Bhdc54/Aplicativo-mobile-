package com.example.peritavision.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.KeyboardType

/*
 * KIT VISUAL — os tijolos da tela.
 * Antes, cada cartão e cada botão eram remontados na mão dentro da MainActivity,
 */

/** Significado de um estado, não sua cor. A cor é escolhida pelo tema. */
enum class Tom { NEUTRO, OK, ATENCAO, ERRO }

@Composable
private fun corDoTom(tom: Tom): Color = when (tom) {
    Tom.NEUTRO -> MaterialTheme.colorScheme.onSurfaceVariant
    Tom.OK -> PvTheme.extras.sucesso
    Tom.ATENCAO -> PvTheme.extras.atencao
    Tom.ERRO -> MaterialTheme.colorScheme.error
}

@Composable
private fun fundoDoTom(tom: Tom): Color = when (tom) {
    Tom.NEUTRO -> MaterialTheme.colorScheme.surfaceVariant
    Tom.OK -> PvTheme.extras.sucessoContainer
    Tom.ATENCAO -> PvTheme.extras.atencaoContainer
    Tom.ERRO -> MaterialTheme.colorScheme.errorContainer
}

// ── Cabeçalho ───────────────────────────────────────────────────────────────

/**
 * Barra de topo. Fica FIXA: o perito precisa saber onde está sem rolar a tela.
 * O subtítulo é contextual — mostra o caso quando há um caso carregado, em vez
 */
@Composable
fun BarraDeTopo(
    titulo: String,
    subtitulo: String,
    @DrawableRes logo: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(id = logo),
            contentDescription = "Logo",
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                titulo,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitulo.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = PvTheme.extras.textoSuave,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

/** Um item da faixa de prontidão. */
data class Prontidao(val rotulo: String, val tom: Tom)

/**
 * Faixa de prontidão — Óculos / Wi-Fi / Sessão sempre à vista.
 * É a correção da falha mais concreta da tela antiga: para saber se dava para
 */
@Composable
fun FaixaProntidao(itens: List<Prontidao>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itens.forEach { item ->
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(fundoDoTom(item.tom))
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(corDoTom(item.tom))
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    item.rotulo,
                    style = MaterialTheme.typography.labelMedium,
                    color = corDoTom(item.tom),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

// ── Cartões ─────────────────────────────────────────────────────────────────

/**
 * Cartão. Com [destaque], ganha borda na cor primária e um leve fundo tonal —
 * é assim que a tela diz "o que importa agora é aqui" sem escrever nada.
 */
@Composable
fun CartaoPv(
    destaque: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = if (destaque) 1.5.dp else 1.dp,
                color = if (destaque) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                shape = MaterialTheme.shapes.medium,
            )
            .padding(15.dp)
            .animateContentSize(),
        content = content,
    )
}

/** Cabeçalho de cartão: rótulo em caixa alta à esquerda, etiqueta de estado à direita. */
@Composable
fun CabecalhoCartao(
    titulo: String,
    etiqueta: String? = null,
    tomEtiqueta: Tom = Tom.NEUTRO,
    aoLado: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            titulo.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = PvTheme.extras.textoSuave,
            modifier = Modifier.weight(1f),
        )
        if (etiqueta != null) Etiqueta(etiqueta, tomEtiqueta)
        aoLado?.invoke(this)
    }
}

@Composable
fun Etiqueta(texto: String, tom: Tom = Tom.NEUTRO) {
    Text(
        texto,
        style = MaterialTheme.typography.labelMedium,
        color = corDoTom(tom),
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(fundoDoTom(tom))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Texto de apoio de um cartão: explica o passo em uma ou duas linhas. */
@Composable
fun TextoApoio(texto: String, tom: Tom? = null) {
    Text(
        texto,
        style = MaterialTheme.typography.bodyMedium,
        color = if (tom != null) corDoTom(tom) else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * Cartão que recolhe. Serve para o que já foi resolvido (servidor configurado,
 * óculos na Wi-Fi): continua acessível a um toque, mas para de disputar atenção
 */
@Composable
fun CartaoRecolhivel(
    titulo: String,
    resumo: String,
    etiqueta: String? = null,
    tomEtiqueta: Tom = Tom.NEUTRO,
    abertoInicial: Boolean = false,
    destaque: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Chaveado por abertoInicial: quando o passo é resolvido (a Wi-Fi conectou,
    // a sessão abriu) o cartão recolhe sozinho, sem o perito ter que arrumar a
    // tela. Enquanto o estado não muda, a escolha manual dele é respeitada.
    var aberto by remember(abertoInicial) { mutableStateOf(abertoInicial) }
    val giro by animateFloatAsState(if (aberto) 180f else 0f, label = "giroChevron")

    CartaoPv(destaque = destaque) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { aberto = !aberto },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    titulo.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = PvTheme.extras.textoSuave,
                )
                Text(
                    resumo,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (etiqueta != null) {
                Etiqueta(etiqueta, tomEtiqueta)
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                painter = painterResource(id = com.example.peritavision.R.drawable.ic_pv_chevron),
                contentDescription = if (aberto) "Recolher" else "Expandir",
                tint = PvTheme.extras.textoSuave,
                modifier = Modifier.size(20.dp).rotate(giro),
            )
        }
        if (aberto) {
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

// ── Botões ──────────────────────────────────────────────────────────────────

/** Ação principal do cartão. Uma por cartão — se houver duas, uma delas não é principal. */
@Composable
fun BotaoPrimario(
    texto: String,
    @DrawableRes icone: Int? = null,
    habilitado: Boolean = true,
    grande: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = habilitado,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = PvTheme.extras.textoSuave,
        ),
        contentPadding = ButtonDefaults.ContentPadding,
        modifier = modifier
            .fillMaxWidth()
            .height(if (grande) 60.dp else 50.dp)
            .padding(top = 0.dp),
    ) { ConteudoBotao(texto, icone, grande) }
}

/** Ação secundária de peso: existe, mas não é a que o dedo procura primeiro. */
@Composable
fun BotaoTonal(
    texto: String,
    @DrawableRes icone: Int? = null,
    habilitado: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = habilitado,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = PvTheme.extras.textoSuave,
        ),
        modifier = modifier.fillMaxWidth().height(48.dp),
    ) { ConteudoBotao(texto, icone, false) }
}

/** Ação terciária: contorno, sem peso de cor. */
@Composable
fun BotaoContorno(
    texto: String,
    @DrawableRes icone: Int? = null,
    habilitado: Boolean = true,
    tom: Tom = Tom.NEUTRO,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = habilitado,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = if (tom == Tom.NEUTRO) MaterialTheme.colorScheme.onSurfaceVariant
            else corDoTom(tom),
            disabledContainerColor = Color.Transparent,
            disabledContentColor = PvTheme.extras.textoSuave,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.fillMaxWidth().height(46.dp),
    ) { ConteudoBotao(texto, icone, false) }
}

@Composable
private fun ConteudoBotao(texto: String, @DrawableRes icone: Int?, grande: Boolean) {
    if (icone != null) {
        Icon(
            painter = painterResource(id = icone),
            contentDescription = null,
            modifier = Modifier.size(if (grande) 22.dp else 19.dp),
        )
        Spacer(Modifier.width(9.dp))
    }
    Text(
        texto,
        style = if (grande) MaterialTheme.typography.titleMedium
        else MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

// ── Campos ──────────────────────────────────────────────────────────────────

/**
 * Campo de texto. Rótulo FORA do campo (não flutuando dentro dele): endereço de
 * IP e SSID são coisas que o perito confere de relance, e rótulo flutuante
 */
@Composable
fun CampoPv(
    valor: String,
    onValueChange: (String) -> Unit,
    rotulo: String,
    habilitado: Boolean = true,
    endereco: Boolean = false,
    /** true = campo de senha: mascara os caracteres e usa teclado de senha. */
    segredo: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            rotulo.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = PvTheme.extras.textoSuave,
            modifier = Modifier.padding(bottom = 5.dp),
        )
        OutlinedTextField(
            value = valor,
            onValueChange = onValueChange,
            singleLine = true,
            enabled = habilitado,
            shape = MaterialTheme.shapes.small,
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(
                keyboardType = when {
                    segredo -> KeyboardType.Password
                    endereco -> KeyboardType.Uri
                    else -> KeyboardType.Text
                }
            ),
            visualTransformation = if (segredo) {
                androidx.compose.ui.text.input.PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                disabledBorderColor = MaterialTheme.colorScheme.outline,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                focusedContainerColor = MaterialTheme.colorScheme.background,
                unfocusedContainerColor = MaterialTheme.colorScheme.background,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ── Dados de custódia ───────────────────────────────────────────────────────

/** Linha chave→valor. Valores numéricos alinhados à direita, para conferir em coluna. */
@Composable
fun LinhaDado(chave: String, valor: String, mono: Boolean = false, ultima: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            chave,
            style = MaterialTheme.typography.bodySmall,
            color = PvTheme.extras.textoSuave,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            valor,
            style = if (mono) {
                MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            } else {
                MaterialTheme.typography.labelMedium
            },
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
    if (!ultima) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

/** Contador grande: quantas fotos já foram seladas. É o número que o perito quer ver. */
@Composable
fun Contador(numero: Int, legenda: String) {
    Row(
        modifier = Modifier.padding(top = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            numero.toString(),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            legenda,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 3.dp),
        )
    }
}

/** Bloco de "estou ouvindo". Vermelho porque é gravação ativa, não porque deu erro. */
@Composable
fun AvisoEscuta(titulo: String, detalhe: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error)
        )
        Spacer(Modifier.width(11.dp))
        Column {
            Text(
                titulo,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                detalhe,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

// ── Rodapé ──────────────────────────────────────────────────────────────────

/**
 * Barra de status FIXA no rodapé. Antes o status era um "Status: ..." solto no
 * meio da rolagem — a mensagem mais importante do app (foto enviada? falhou?)
 */
@Composable
fun BarraDeStatus(texto: String, tom: Tom) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(corDoTom(tom))
        )
        Spacer(Modifier.width(9.dp))
        Text(
            texto,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun RodapeMarca(empresa: String) {
    Text(
        "PeritaVision · desenvolvido por $empresa".uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = PvTheme.extras.textoSuave,
        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}
