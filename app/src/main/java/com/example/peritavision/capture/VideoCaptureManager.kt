package com.example.peritavision.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Gravacao de video via CameraX. Travado em 720p (Quality.HD) de proposito:
 * e o teto real do Mentra Live (GAP-01 / RF-06). Assim o simulador ja se
 */
class VideoCaptureManager {

    private val recorder: Recorder = Recorder.Builder()
        .setQualitySelector(QualitySelector.from(Quality.HD)) // 1280x720
        .build()

    /** Use case que o device liga a camera junto com o preview. */
    val videoCapture: VideoCapture<Recorder> = VideoCapture.withOutput(recorder)

    private var recording: Recording? = null

    val gravando: Boolean get() = recording != null

    /**
     * Inicia a gravacao no [destino]. Ao finalizar, chama [onSalvo] com o
     * arquivo; em falha, chama [onErro].
     */
    @SuppressLint("MissingPermission")
    fun iniciar(
        context: Context,
        destino: File,
        onSalvo: (File) -> Unit,
        onErro: (String) -> Unit
    ) {
        if (recording != null) return // ja gravando
        val arquivo = File(destino, nomeArquivo("VIDEO", "mp4"))
        val opcoes = FileOutputOptions.Builder(arquivo).build()

        var pending = videoCapture.output.prepareRecording(context, opcoes)
        // So habilita trilha de audio se a permissao ja estiver concedida.
        val podeAudio = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (podeAudio) pending = pending.withAudioEnabled()

        recording = pending.start(ContextCompat.getMainExecutor(context)) { evento ->
            if (evento is VideoRecordEvent.Finalize) {
                recording = null
                if (evento.hasError()) {
                    onErro("erro de gravacao (codigo ${evento.error})")
                } else {
                    onSalvo(arquivo)
                }
            }
        }
    }

    /** Encerra a gravacao; o resultado chega pelo callback de [iniciar]. */
    fun parar() {
        recording?.stop()
        recording = null
    }
}
