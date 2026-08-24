package com.example.peritavision.capture

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Gravacao de audio via MediaRecorder (independente da camera).
 * Salva em .m4a (MPEG-4 / AAC).
 */
class AudioCaptureManager {

    private var recorder: MediaRecorder? = null
    private var arquivoAtual: File? = null

    val gravando: Boolean get() = recorder != null

    /** Inicia a gravacao no [destino]. Retorna o arquivo sendo gravado. */
    fun iniciar(context: Context, destino: File): File {
        val arquivo = File(destino, nomeArquivo("AUDIO", "m4a"))
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(128_000)
        r.setAudioSamplingRate(44_100)
        r.setOutputFile(arquivo.absolutePath)
        r.prepare()
        r.start()

        recorder = r
        arquivoAtual = arquivo
        return arquivo
    }

    /**
     * Encerra a gravacao e devolve o arquivo final, ou null se nada estava
     * sendo gravado ou se a gravacao foi curta demais (MediaRecorder falha
     */
    fun parar(): File? {
        val arquivo = arquivoAtual
        val r = recorder ?: return null
        return try {
            r.stop()
            arquivo
        } catch (_: RuntimeException) {
            // stop() logo apos start() lanca IllegalStateException; arquivo invalido
            arquivo?.delete()
            null
        } finally {
            r.release()
            recorder = null
            arquivoAtual = null
        }
    }
}
