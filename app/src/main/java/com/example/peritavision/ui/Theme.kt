package com.example.peritavision.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/*
 * TEMA — claro e escuro, seguindo o celular.
 * Duas coisas estavam erradas antes e foram corrigidas aqui:
 */

/**
 * Cores que o Material 3 não tem slot para: "sucesso" e "atenção".
 * O esquema do Material só oferece primary/secondary/tertiary/error. Sucesso e
 */
@Immutable
data class CoresExtras(
    val sucesso: Color,
    val sucessoContainer: Color,
    val atencao: Color,
    val atencaoContainer: Color,
    /** Texto de terceira ordem: rótulos, carimbos, rodapé. */
    val textoSuave: Color,
)

private val ExtrasClaro = CoresExtras(
    sucesso = Verde40,
    sucessoContainer = Verde90,
    atencao = Ambar40,
    atencaoContainer = Ambar90,
    textoSuave = Ardosia400,
)

private val ExtrasEscuro = CoresExtras(
    sucesso = Verde80,
    sucessoContainer = Verde15,
    atencao = Ambar80,
    atencaoContainer = Ambar15,
    textoSuave = NoiteTexto3,
)

private val LocalCoresExtras = staticCompositionLocalOf { ExtrasClaro }

/** Acesso às cores extras: `PvTheme.extras.sucesso`, igual a MaterialTheme.colorScheme. */
object PvTheme {
    val extras: CoresExtras
        @Composable @ReadOnlyComposable get() = LocalCoresExtras.current
}

private val EsquemaClaro = lightColorScheme(
    primary = Azul40,
    onPrimary = Branco,
    primaryContainer = Azul90,
    onPrimaryContainer = Azul10,
    inversePrimary = Azul80,
    secondary = Ardosia500,
    onSecondary = Branco,
    secondaryContainer = Ardosia100,
    onSecondaryContainer = Ardosia900,
    tertiary = Verde40,
    onTertiary = Branco,
    tertiaryContainer = Verde90,
    onTertiaryContainer = Verde15,
    background = Ardosia50,
    onBackground = Ardosia900,
    surface = Branco,
    onSurface = Ardosia900,
    surfaceVariant = Ardosia100,
    onSurfaceVariant = Ardosia500,
    surfaceContainerLowest = Branco,
    surfaceContainerLow = Branco,
    surfaceContainer = Ardosia50,
    surfaceContainerHigh = Ardosia100,
    surfaceContainerHighest = Ardosia100,
    surfaceTint = Azul40,
    outline = Ardosia200,
    outlineVariant = Ardosia200,
    inverseSurface = Ardosia900,
    inverseOnSurface = Ardosia50,
    error = Vermelho40,
    onError = Branco,
    errorContainer = Vermelho90,
    onErrorContainer = Vermelho15,
    scrim = Color.Black,
)

private val EsquemaEscuro = darkColorScheme(
    primary = Azul80,
    onPrimary = Azul10,
    primaryContainer = Azul30,
    onPrimaryContainer = Azul90,
    inversePrimary = Azul40,
    secondary = NoiteTexto2,
    onSecondary = Noite900,
    secondaryContainer = Noite700,
    onSecondaryContainer = NoiteTexto,
    tertiary = Verde80,
    onTertiary = Verde15,
    tertiaryContainer = Verde15,
    onTertiaryContainer = Verde80,
    background = Noite900,
    onBackground = NoiteTexto,
    surface = Noite800,
    onSurface = NoiteTexto,
    surfaceVariant = Noite700,
    onSurfaceVariant = NoiteTexto2,
    surfaceContainerLowest = Noite900,
    surfaceContainerLow = Noite800,
    surfaceContainer = Noite800,
    surfaceContainerHigh = Noite700,
    surfaceContainerHighest = Noite700,
    surfaceTint = Azul80,
    outline = Noite600,
    outlineVariant = Noite600,
    inverseSurface = NoiteTexto,
    inverseOnSurface = Noite900,
    error = Vermelho80,
    onError = Vermelho15,
    errorContainer = Vermelho15,
    onErrorContainer = Vermelho80,
    scrim = Color.Black,
)

/** Cantos: nada quadrado, nada de bolha. 10/14/18 dp é o suficiente. */
private val FormasPv = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun PeritavisionTheme(
    // TEMA CLARO SEMPRE (24/08/2026): o app nao segue mais o modo escuro do
    // celular/tablet — bancada pede fundo branco e a cor semantica (verde=ok,
    // ambar=atencao, vermelho=falhou) fica mais legivel sobre claro. O esquema
    // escuro continua definido acima; para voltar a seguir o sistema, troque o
    // default por isSystemInDarkTheme() (e reimporte
    // androidx.compose.foundation.isSystemInDarkTheme).
    temaEscuro: Boolean = false,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalCoresExtras provides if (temaEscuro) ExtrasEscuro else ExtrasClaro
    ) {
        MaterialTheme(
            colorScheme = if (temaEscuro) EsquemaEscuro else EsquemaClaro,
            typography = TipografiaPv,
            shapes = FormasPv,
            content = content,
        )
    }
}
