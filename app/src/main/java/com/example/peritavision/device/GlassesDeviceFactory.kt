package com.example.peritavision.device

import android.content.Context
import java.io.File

/**
 * Ponto UNICO de injecao do dispositivo.
 * A UI (MainActivity) e o dominio (custodia) so conhecem a interface
 */
object GlassesDeviceFactory {

    enum class Tipo { MENTRA, PHONE }

    fun create(
        context: Context,
        destino: File,
        tipo: Tipo = Tipo.MENTRA,
        mentraConfig: MentraGlassesDevice.MentraConfig = MentraGlassesDevice.MentraConfig(),
    ): GlassesDevice = when (tipo) {
        Tipo.MENTRA -> MentraGlassesDevice(context, destino, mentraConfig)
        Tipo.PHONE -> PhoneGlassesDevice(context, destino)
    }
}
