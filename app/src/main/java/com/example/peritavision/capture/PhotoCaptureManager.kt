package com.example.peritavision.capture

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Captura de foto via CameraX. Detalhe interno do PhoneGlassesDevice — nao
 * expoe tipos do CameraX para o dominio, apenas o [ImageCapture] use case para
 */
class PhotoCaptureManager {

    /** Use case que o device liga a camera junto com o preview. */
    val imageCapture: ImageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .build()

    /**
     * Tira a foto e salva no [destino]. Chama [onSalvo] com o arquivo final
     * (a custodia — hash/GPS/log — acontece depois, fora daqui).
     */
    fun capturar(
        context: Context,
        destino: File,
        onSalvo: (File) -> Unit,
        onErro: (String) -> Unit
    ) {
        val arquivo = File(destino, nomeArquivo("FOTO", "jpg"))
        val opcoes = ImageCapture.OutputFileOptions.Builder(arquivo).build()
        imageCapture.takePicture(
            opcoes,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onSalvo(arquivo)
                }

                override fun onError(exc: ImageCaptureException) {
                    onErro(exc.message ?: "falha ao capturar foto")
                }
            }
        )
    }
}
