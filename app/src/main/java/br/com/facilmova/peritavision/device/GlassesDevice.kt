package br.com.facilmova.peritavision.device

import br.com.facilmova.peritavision.domain.TipoEvidencia
import kotlinx.coroutines.flow.SharedFlow
import java.io.File

/** Abstracao do "oculos". */
interface GlassesDevice {

    /** Fluxo de eventos assincronos emitidos pelo dispositivo. */
    val eventos: SharedFlow<GlassesEvent>

    /** Dispara a captura de uma foto (botao da tela hoje; haste do oculos amanha). */
    fun capturarFoto()

    /** Inicia o video da sessao (Mentra: stream RTMP para urlStream; celular: grava local). */
    fun iniciarVideo(urlStream: String? = null)

    /** Encerra a gravacao de video em andamento. */
    fun pararVideo()

    /** Inicia a gravacao de audio. */
    fun iniciarAudio()

    /** Encerra a gravacao de audio em andamento. */
    fun pararAudio()

    /** Libera recursos (camera/mic). Chamar ao encerrar a tela/sessao. */
    fun encerrar()
}

/** Eventos do dispositivo. */
sealed interface GlassesEvent {
    /** Um arquivo de evidencia acabou de ser gravado no destino (custodia local). */
    data class ArquivoCapturado(val tipo: TipoEvidencia, val arquivo: File) : GlassesEvent

    /** Captura enviada por Wi-Fi DIRETO ao backend (modelo do Mentra Live via requestPhoto → webhook). */
    data class CapturaRemota(
        val tipo: TipoEvidencia,
        val requestId: String,
        val uploadUrl: String?,
        /** false = não é foto: é uma MARCA no vídeo, e o quadro só vira imagem se o servidor conseguir recortá-lo no fim. */
        val fotoDeVerdade: Boolean = true,
    ) : GlassesEvent

    /** Os óculos RECUSARAM a foto na hora — "Camera busy with streaming": o Mentra Live não fotografa enquanto transmite vídeo. */
    data class FotoRecusada(
        val requestId: String,
        val motivo: String,
        val webhookUrl: String,
        val authToken: String,
    ) : GlassesEvent

    /** Uma gravacao (video/audio) comecou. */
    data class GravacaoIniciada(val tipo: TipoEvidencia) : GlassesEvent

    /** Estado da conexao com os oculos mudou (usado pelo Mentra Live por BLE). */
    data class Conexao(val conectado: Boolean) : GlassesEvent

    /** Estado do Wi-Fi DOS OCULOS. */
    data class Wifi(val conectado: Boolean, val ssid: String?) : GlassesEvent

    /** Falha em alguma operacao de captura. A tela mostra como "Erro: ...". */
    data class Erro(val mensagem: String) : GlassesEvent

    /** Mensagem informativa (nao e falha): "óculos ouvindo", "enviando Wi-Fi"... */
    data class Aviso(val mensagem: String) : GlassesEvent

    /** O microfone dos oculos funciona, mas a transcricao local nao devolveu nada. */
    data object TranscricaoIndisponivel : GlassesEvent
}
