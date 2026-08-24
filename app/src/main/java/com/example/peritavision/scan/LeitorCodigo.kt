package com.example.peritavision.scan

import android.content.Context
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Leitor do codigo do lacre com a tela pronta do Google Play Services
 * (sem permissao de camera nem layout proprio): abre, le e devolve o texto cru.
 */
object LeitorCodigo {
    fun ler(context: Context, onOk: (String) -> Unit, onErro: (String) -> Unit) {
        val opcoes = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_DATA_MATRIX,
            )
            .enableAutoZoom()
            .build()
        val scanner = GmsBarcodeScanning.getClient(context, opcoes)

        ModuleInstall.getClient(context)
            .installModules(ModuleInstallRequest.newBuilder().addApi(scanner).build())
            .addOnCompleteListener {
                scanner.startScan()
                    .addOnSuccessListener { b ->
                        val texto = b.rawValue?.trim().orEmpty()
                        if (texto.isEmpty()) onErro("codigo vazio - tente de novo")
                        else onOk(texto)
                    }
                    .addOnCanceledListener { }
                    .addOnFailureListener { e -> onErro(e.message ?: "falha ao ler o codigo") }
            }
    }
}
