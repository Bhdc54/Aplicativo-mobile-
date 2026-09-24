package br.com.facilmova.peritavision.data

import android.content.Context
import android.net.wifi.WifiManager

/** A REDE EM QUE O TABLET ESTÁ AGORA. */
data class RedeDoTablet(
    val ssid: String?,
    /** Frequência em MHz (0 = desconhecida). ~2400 = 2,4 GHz, ~5000 = 5 GHz. */
    val frequenciaMhz: Int,
) {
    /** 5 GHz ou 6 GHz: os óculos não enxergam esta rede. */
    val foraDoAlcanceDosOculos: Boolean get() = frequenciaMhz >= 4_000

    /** "2,4 GHz", "5 GHz", "6 GHz" — ou vazio quando não dá para saber. */
    val banda: String
        get() = when {
            frequenciaMhz <= 0 -> ""
            frequenciaMhz >= 5_900 -> "6 GHz"
            frequenciaMhz >= 4_000 -> "5 GHz"
            else -> "2,4 GHz"
        }
}

/** Lê a rede Wi-Fi atual do tablet. */
@Suppress("DEPRECATION") // getConnectionInfo: obsoleto, mas é o que ainda entrega SSID + frequência juntos
fun redeAtualDoTablet(context: Context): RedeDoTablet? {
    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        ?: return null
    if (!runCatching { wm.isWifiEnabled }.getOrDefault(false)) return null
    val info = runCatching { wm.connectionInfo }.getOrNull() ?: return null
    // O SSID vem entre aspas; "<unknown ssid>" é o que o Android devolve
    // quando não quer contar (fora de rede, ou sem permissão de localização).
    val bruto = info.ssid.orEmpty().trim().removeSurrounding("\"")
    val ssid = bruto.takeIf {
        it.isNotBlank() && !it.equals("<unknown ssid>", ignoreCase = true) && it != "0x"
    }
    val frequencia = runCatching { info.frequency }.getOrDefault(0)
    if (ssid == null && frequencia <= 0) return null
    return RedeDoTablet(ssid, frequencia)
}
