package com.example.peritavision.device

import com.example.peritavision.domain.TipoEvidencia
import kotlinx.coroutines.flow.SharedFlow
import java.io.File

/**
 * Abstracao do "oculos".
 * A UI e o dominio NUNCA falam com CameraX ou com o Mentra SDK diretamente —
 */
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

/**
 * Eventos do dispositivo. O arquivo chega "cru"; quem sela a custodia
 * (SHA-256 + GPS + log) e a camada de dominio, fora do device.
 */
sealed interface GlassesEvent {
    /** Um arquivo de evidencia acabou de ser gravado no destino (custodia local). */
    data class ArquivoCapturado(val tipo: TipoEvidencia, val arquivo: File) : GlassesEvent

    /**
     * Captura enviada por Wi-Fi DIRETO ao backend (modelo do Mentra Live via
     * requestPhoto → webhook). Nao ha File local no telefone: a custodia
     */
    data class CapturaRemota(
        val tipo: TipoEvidencia,
        val requestId: String,
        val uploadUrl: String?,
    ) : GlassesEvent

    /** Uma gravacao (video/audio) comecou. */
    data class GravacaoIniciada(val tipo: TipoEvidencia) : GlassesEvent

    /** Estado da conexao com os oculos mudou (usado pelo Mentra Live por BLE). */
    data class Conexao(val conectado: Boolean) : GlassesEvent

    /**
     * Estado do Wi-Fi DOS OCULOS. O Bluetooth so manda o comando; quem sobe o
     * JPEG para o backend e o proprio oculos, pela rede Wi-Fi dele. Sem Wi-Fi
     */
    data class Wifi(val conectado: Boolean, val ssid: String?) : GlassesEvent

    /** Falha em alguma operacao de captura. A tela mostra como "Erro: ...". */
    data class Erro(val mensagem: String) : GlassesEvent

    /**
     * Mensagem informativa (nao e falha): "óculos ouvindo", "enviando Wi-Fi"...
     * Separado de [Erro] para a tela nao rotular aviso normal como erro.
     */
    data class Aviso(val mensagem: String) : GlassesEvent

    /**
     * O microfone dos oculos funciona, mas a transcricao local nao devolveu
     * nada. A tela usa isso para cair automaticamente no reconhecimento de voz
     */
    data object TranscricaoIndisponivel : GlassesEvent
}
