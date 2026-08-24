package com.example.peritavision.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Disparo por VOZ, no proprio celular, SEM backend.
 * Escuta continuamente com o SpeechRecognizer do Android e, ao ouvir uma
 */
class VoiceTrigger(
    private val context: Context,
    private val palavrasChave: List<String> = listOf("capturar", "captura", "foto", "registrar"),
    private val onComando: () -> Unit,
    /** Feedback para a tela mostrar o que o reconhecedor esta fazendo. */
    var onStatus: ((String) -> Unit)? = null,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var ativo = false
    private var ultimoDisparoMs = 0L

    /** true quando estamos usando o reconhecedor offline do proprio aparelho. */
    private var modoOffline = false

    /** Comeca a escutar. Pode ser chamado de qualquer thread. */
    fun iniciar() {
        if (ativo) return

        val temPadrao = SpeechRecognizer.isRecognitionAvailable(context)
        // Android 12+ tem um reconhecedor OFFLINE embutido. Serve de plano B
        // quando o servico normal (app do Google) nao esta disponivel.
        val temOffline = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        if (!temPadrao && !temOffline) {
            avisar(
                "Reconhecimento de voz indisponível. Instale/ative o app Google " +
                    "e o \"Reconhecimento de voz do Google\" nas configurações."
            )
            return
        }
        modoOffline = !temPadrao

        ativo = true
        handler.post {
            recognizer = try {
                if (modoOffline && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
            } catch (e: Exception) {
                avisar("Não consegui iniciar o reconhecedor: ${e.message}")
                ativo = false
                return@post
            }.apply { setRecognitionListener(listener) }
            escutar()
        }
    }

    /** startListening TEM de rodar na main thread. */
    private fun escutar() {
        if (!ativo) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // Reduz a espera de silencio: reage mais rapido a uma palavra solta.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800L)
            // Sem internet o reconhecedor padrao tambem funciona offline quando
            // o pacote de idioma pt-BR estiver baixado no aparelho.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, modoOffline)
        }
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startListening falhou: ${e.message}")
            reiniciar(atrasoMs = 800)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            avisar("Ouvindo… diga \"capturar\"")
        }

        override fun onResults(results: Bundle?) {
            val disparou = avaliar(results)
            // Depois de um resultado final o servico precisa de um respiro.
            reiniciar(atrasoMs = if (disparou) 1500 else 350)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            avaliar(partialResults)
        }

        override fun onError(error: Int) {
            // NO_MATCH e SPEECH_TIMEOUT sao normais (silencio): so reescuta.
            // RECOGNIZER_BUSY e SERVER pedem uma pausa maior.
            val atraso = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 300L
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1200L
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    avisar("Permissão de microfone negada")
                    ativo = false
                    return
                }
                else -> 900L
            }
            reiniciar(atraso)
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** @return true se disparou o comando. */
    private fun avaliar(bundle: Bundle?): Boolean {
        val textos = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return false
        if (textos.isEmpty()) return false

        val ouviu = textos.any { frase ->
            val f = normalizar(frase)
            palavrasChave.any { chave -> f.contains(normalizar(chave)) }
        }
        if (!ouviu) return false

        // Debounce: o parcial e o final da mesma fala nao podem disparar duas vezes.
        if (System.currentTimeMillis() - ultimoDisparoMs <= 2500) return false
        ultimoDisparoMs = System.currentTimeMillis()
        avisar("Comando reconhecido: \"${textos.first()}\"")
        onComando()
        return true
    }

    /** minusculas e sem acento, para "capturá" casar com "capturar". */
    private fun normalizar(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(Locale.getDefault()), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    private fun reiniciar(atrasoMs: Long) {
        if (!ativo) return
        handler.postDelayed({
            if (!ativo) return@postDelayed
            runCatching { recognizer?.cancel() }
            escutar()
        }, atrasoMs)
    }

    private fun avisar(msg: String) {
        Log.d(TAG, msg)
        handler.post { onStatus?.invoke(msg) }
    }

    /** Para de escutar e libera o microfone. */
    fun encerrar() {
        ativo = false
        handler.removeCallbacksAndMessages(null)
        handler.post {
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
    }

    companion object { private const val TAG = "VoiceTrigger" }
}
