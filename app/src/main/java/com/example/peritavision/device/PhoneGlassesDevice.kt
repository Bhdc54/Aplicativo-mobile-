package com.example.peritavision.device

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.peritavision.capture.AudioCaptureManager
import com.example.peritavision.capture.PhotoCaptureManager
import com.example.peritavision.capture.VideoCaptureManager
import com.example.peritavision.domain.TipoEvidencia
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

/**
 * Implementacao de [GlassesDevice] usando a camera e o microfone do CELULAR.
 * E a "muleta de teste" enquanto o Mentra Live nao chega: a camera do phone
 */
class PhoneGlassesDevice(
    private val context: Context,
    private val destino: File
) : GlassesDevice {

    private val _eventos = MutableSharedFlow<GlassesEvent>(extraBufferCapacity = 16)
    override val eventos: SharedFlow<GlassesEvent> = _eventos.asSharedFlow()

    private val foto = PhotoCaptureManager()
    private val video = VideoCaptureManager()
    private val audio = AudioCaptureManager()

    private var cameraProvider: ProcessCameraProvider? = null

    /**
     * Vincula a camera do celular ao [previewView] e ao ciclo de vida.
     * Metodo especifico do phone (nao esta na interface): o preview e uma
     */
    fun vincularCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    foto.imageCapture,
                    video.videoCapture
                )
            } catch (e: Exception) {
                _eventos.tryEmit(GlassesEvent.Erro("falha ao iniciar camera: ${e.message}"))
            }
        }, ContextCompat.getMainExecutor(context))
    }

    override fun capturarFoto() {
        foto.capturar(
            context = context,
            destino = destino,
            onSalvo = { arquivo ->
                _eventos.tryEmit(GlassesEvent.ArquivoCapturado(TipoEvidencia.FOTO, arquivo))
            },
            onErro = { msg -> _eventos.tryEmit(GlassesEvent.Erro(msg)) }
        )
    }

    override fun iniciarVideo(urlStream: String?) {
        _eventos.tryEmit(GlassesEvent.GravacaoIniciada(TipoEvidencia.VIDEO))
        video.iniciar(
            context = context,
            destino = destino,
            onSalvo = { arquivo ->
                _eventos.tryEmit(GlassesEvent.ArquivoCapturado(TipoEvidencia.VIDEO, arquivo))
            },
            onErro = { msg -> _eventos.tryEmit(GlassesEvent.Erro(msg)) }
        )
    }

    override fun pararVideo() {
        video.parar()
    }

    override fun iniciarAudio() {
        try {
            audio.iniciar(context, destino)
            _eventos.tryEmit(GlassesEvent.GravacaoIniciada(TipoEvidencia.AUDIO))
        } catch (e: Exception) {
            _eventos.tryEmit(GlassesEvent.Erro("falha ao iniciar audio: ${e.message}"))
        }
    }

    override fun pararAudio() {
        val arquivo = audio.parar()
        if (arquivo != null) {
            _eventos.tryEmit(GlassesEvent.ArquivoCapturado(TipoEvidencia.AUDIO, arquivo))
        } else {
            _eventos.tryEmit(GlassesEvent.Erro("audio muito curto ou nao gravado"))
        }
    }

    override fun encerrar() {
        video.parar()
        audio.parar()
        cameraProvider?.unbindAll()
        cameraProvider = null
    }
}
