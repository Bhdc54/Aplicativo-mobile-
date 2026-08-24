package com.example.peritavision.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Confirmação FALADA de que algo aconteceu ("Foto capturada", "Evidência
 * selada"...) — o feedback sonoro que o MVP descreve, para o perito confirmar
 */
class FeedbackDeVoz(context: Context) {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var avisouVolume = false
    private var motor: TextToSpeech? = null

    // Fone Bluetooth (óculos via HFP): liga o canal SCO só enquanto fala, para
    // não segurar o microfone dos óculos suspenso o resto do tempo.
    private var scoLigado = false
    private val falasPendentes = AtomicInteger(0)

    /** So desvia para o fone Bluetooth se ele estiver REALMENTE conectado. */
    private fun temFoneBluetooth(): Boolean {
        val am = audioManager ?: return false
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
    }

    private fun ligarSco() {
        val am = audioManager ?: return
        if (!scoLigado && temFoneBluetooth() && am.isBluetoothScoAvailableOffCall) {
            runCatching {
                @Suppress("DEPRECATION") am.startBluetoothSco()
                @Suppress("DEPRECATION") am.isBluetoothScoOn = true
                scoLigado = true
            }
        }
    }

    private fun desligarSco() {
        val am = audioManager ?: return
        if (scoLigado) {
            runCatching {
                @Suppress("DEPRECATION") am.stopBluetoothSco()
                @Suppress("DEPRECATION") am.isBluetoothScoOn = false
            }
            scoLigado = false
        }
    }
    private var pronto = false
    private val filaAntesDePronto = mutableListOf<String>()

    /** Avisos de diagnóstico (idioma ausente, erro ao falar...). */
    var onStatus: ((String) -> Unit)? = null

    init {
        motor = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TextToSpeech não iniciou (status=$status) — sem confirmação falada")
                onStatus?.invoke("Motor de voz do celular não iniciou — sem confirmação falada")
                return@TextToSpeech
            }
            val m = motor ?: return@TextToSpeech
            val idioma = escolherIdiomaDisponivel(m)
            if (idioma == null) {
                Log.w(TAG, "nenhum pacote de voz disponível no aparelho")
                onStatus?.invoke(
                    "Nenhum pacote de voz instalado no celular — instale em " +
                        "Configurações > Acessibilidade > Conversão de texto em fala"
                )
                return@TextToSpeech
            }
            m.setSpeechRate(1.05f)
            m.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (falasPendentes.decrementAndGet() <= 0) desligarSco()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.w(TAG, "falha ao falar: $utteranceId")
                    if (falasPendentes.decrementAndGet() <= 0) desligarSco()
                }
            })
            pronto = true
            filaAntesDePronto.forEach { motor?.speak(it, TextToSpeech.QUEUE_ADD, null, it) }
            filaAntesDePronto.clear()
        }
    }

    /**
     * Tenta pt-BR; se faltar o pacote, cai para o idioma padrão do aparelho;
     * se esse também faltar, cai para inglês. Sempre deixa ALGO configurado
     */
    private fun escolherIdiomaDisponivel(m: TextToSpeech): Locale? {
        val candidatos = listOf(Locale("pt", "BR"), Locale.getDefault(), Locale.US)
        for (loc in candidatos) {
            val r = m.setLanguage(loc)
            if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) {
                if (loc != candidatos.first()) {
                    onStatus?.invoke("Voz em português ausente — usando $loc")
                }
                return loc
            }
        }
        return null
    }

    /** Fala a frase. Enfileira (QUEUE_ADD) — várias confirmações não se cortam. */
    fun falar(texto: String) {
        // TTS sai pelo volume de MÍDIA: no mudo, a fala existe mas ninguém ouve.
        val vol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 1
        if (vol == 0 && !avisouVolume) {
            avisouVolume = true
            onStatus?.invoke("Volume de mídia no MUDO — aumente para ouvir as confirmações faladas")
        }
        if (pronto) {
            ligarSco() // com os óculos pareados como fone, a fala sai NELES
            falasPendentes.incrementAndGet()
            val params = Bundle().apply {
                if (scoLigado) {
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_VOICE_CALL)
                }
            }
            motor?.speak(texto, TextToSpeech.QUEUE_ADD, params, texto)
        } else {
            filaAntesDePronto += texto  // TTS ainda carregando: fala assim que ficar pronto
        }
    }

    fun encerrar() {
        desligarSco()
        runCatching { motor?.stop() }
        runCatching { motor?.shutdown() }
        motor = null
    }

    companion object { private const val TAG = "FeedbackDeVoz" }
}
