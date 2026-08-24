package com.example.peritavision.capture

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Gera nome de arquivo com prefixo e timestamp: ex. FOTO_20260720_143001.jpg */
internal fun nomeArquivo(prefixo: String, extensao: String): String {
    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    return "${prefixo}_${ts}.${extensao}"
}
