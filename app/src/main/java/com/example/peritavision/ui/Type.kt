package com.example.peritavision.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * TIPOGRAFIA — Roboto (fonte do sistema), escala completa.
 * Antes só bodyLarge estava definido; todo o resto vinha do padrão e os tamanhos
 */

private fun estilo(
    tamanho: Double,
    altura: Double,
    peso: FontWeight,
    espacamento: Double = 0.0,
) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = peso,
    fontSize = tamanho.sp,
    lineHeight = altura.sp,
    letterSpacing = espacamento.sp,
)

val TipografiaPv = Typography(
    // Números grandes (contador de fotos).
    displaySmall = estilo(30.0, 34.0, FontWeight.Bold, -0.8),
    headlineMedium = estilo(24.0, 30.0, FontWeight.Bold, -0.4),
    // Nome do app, número do caso.
    headlineSmall = estilo(19.0, 25.0, FontWeight.Bold, -0.3),
    // Título de cartão em destaque.
    titleLarge = estilo(17.0, 23.0, FontWeight.Bold, -0.2),
    titleMedium = estilo(15.0, 20.0, FontWeight.SemiBold),
    titleSmall = estilo(13.5, 18.0, FontWeight.SemiBold),
    // Texto corrido, explicações dos cartões.
    bodyLarge = estilo(15.0, 22.0, FontWeight.Normal),
    bodyMedium = estilo(13.5, 20.0, FontWeight.Normal),
    bodySmall = estilo(12.0, 17.0, FontWeight.Normal),
    // Botões.
    labelLarge = estilo(14.5, 20.0, FontWeight.Bold, 0.2),
    // Etiquetas de estado, valores de custódia.
    labelMedium = estilo(11.5, 16.0, FontWeight.SemiBold, 0.3),
    // CABEÇALHOS DE SEÇÃO EM CAIXA ALTA — precisa de entrelinha generosa.
    labelSmall = estilo(10.5, 14.0, FontWeight.Bold, 0.9),
)
