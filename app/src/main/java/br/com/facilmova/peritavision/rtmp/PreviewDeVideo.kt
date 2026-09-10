// O QUE OS ÓCULOS ESTÃO VENDO, NA TELA DO TABLET (08/09/2026).
//
// O cartão "Visão dos óculos" no modo tablet mostrava só números — quadros por
// segundo, megabytes, número do segmento — porque o receptor RTMP entrega
// H.264 cru e ninguém decodificava. Em campo o perito olhou para o retângulo
// preto e disse o obvio: "o vídeo não aparece no tablet". Os quadros estavam
// chegando; faltava mostrá-los.
//
// Aqui o H.264 do RtmpIngest vai para o MediaCodec do tablet, que desenha
// direto numa Surface. Sem cópia de bitmap, sem conversão de cor em Kotlin: o
// decodificador de hardware escreve na superfície e a tela mostra.
//
// O que a tag FLV entrega em `Evento.Quadro.dados`:
//   byte 0      → frameType (4 bits) | codecId (4 bits); codecId 7 = AVC
//   byte 1      → AVCPacketType: 0 = cabeçalho de sequência, 1 = NALU
//   bytes 2..4  → composition time offset (não usamos)
//   bytes 5..   → no cabeçalho: AVCDecoderConfigurationRecord (SPS/PPS)
//                 no NALU: AVCC, cada NALU precedida do seu tamanho
// O MediaCodec quer Annex-B (00 00 00 01 antes de cada NALU), então a
// conversão acontece na entrega.
package br.com.facilmova.peritavision.rtmp

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class DecodificadorDeVideo {

    /** Estado que a tela mostra ao lado da imagem. */
    data class Estado(
        val decodificando: Boolean = false,
        val largura: Int = 0,
        val altura: Int = 0,
        val quadrosNaTela: Int = 0,
        val quadrosDescartados: Int = 0,
        val erro: String? = null,
    )

    @Volatile var estado = Estado()
        private set
    var aoMudar: ((Estado) -> Unit)? = null

    private class Pacote(val annexB: ByteArray, val ptsUs: Long, val keyframe: Boolean)

    private val fila = ArrayBlockingQueue<Pacote>(FILA)
    private val trava = Any()

    private var codec: MediaCodec? = null
    private var trabalhador: Thread? = null
    @Volatile private var rodando = false

    @Volatile private var superficie: Surface? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    /** bytes do prefixo de tamanho no AVCC (lengthSizeMinusOne + 1) */
    private var prefixo = 4
    /** Depois de (re)começar, só o keyframe abre a imagem: entregar um quadro
     *  intermediário primeiro produz o "vídeo derretido" clássico. */
    @Volatile private var esperandoKeyframe = true

    // ------------------------------------------------------------------------
    // Superfície (vem do SurfaceView, na thread principal)
    // ------------------------------------------------------------------------

    fun definirSuperficie(nova: Surface?) {
        synchronized(trava) {
            if (superficie === nova) return
            superficie = nova
            pararCodec()
            fila.clear()
            if (nova != null && sps != null && pps != null) iniciarCodec()
            else publicar { it.copy(decodificando = false) }
        }
    }

    // ------------------------------------------------------------------------
    // Entrada: cada quadro do RtmpIngest (thread de rede)
    // ------------------------------------------------------------------------

    fun aceitar(ev: RtmpIngest.Evento.Quadro) {
        val d = ev.dados
        if (d.size < 6) return
        val codecId = d[0].toInt() and 0x0f
        if (codecId != 7) return // só AVC/H.264 (é o que os óculos publicam)

        if (ev.cabecalhoDeSequencia) {
            lerConfiguracao(d)
            return
        }
        // Sem superfície (cartão fora da tela, tela apagada para luz forense)
        // não há para quem desenhar: sair aqui evita converter e alocar um
        // buffer por quadro à toa, trinta vezes por segundo, a perícia inteira.
        if (superficie == null) return
        val annexB = paraAnnexB(d) ?: return
        if (esperandoKeyframe) {
            if (!ev.keyframe) { descartar(); return }
            esperandoKeyframe = false
        }
        val pacote = Pacote(annexB, ev.timestampMs.toLong() * 1000L, ev.keyframe)
        if (!fila.offer(pacote)) {
            // Fila cheia = o decodificador não está acompanhando (tela apagada,
            // aparelho ocupado). Descarta o MAIS ANTIGO, não o novo: no vídeo ao
            // vivo o quadro velho não interessa mais a ninguém.
            fila.poll()
            descartar()
            if (!fila.offer(pacote)) descartar()
        }
    }

    /** AVCDecoderConfigurationRecord → SPS e PPS, e (re)partida do codec. */
    private fun lerConfiguracao(d: ByteArray) {
        try {
            var i = 5
            if (i + 6 > d.size) return
            // d[i] = configurationVersion, +1 profile, +2 compat, +3 level
            prefixo = (d[i + 4].toInt() and 0x03) + 1
            var n = d[i + 5].toInt() and 0x1f
            i += 6
            var novoSps: ByteArray? = null
            var novoPps: ByteArray? = null
            repeat(n) {
                if (i + 2 > d.size) return
                val tam = ((d[i].toInt() and 0xff) shl 8) or (d[i + 1].toInt() and 0xff)
                i += 2
                if (tam <= 0 || i + tam > d.size) return
                if (novoSps == null) novoSps = d.copyOfRange(i, i + tam)
                i += tam
            }
            if (i >= d.size) return
            n = d[i].toInt() and 0xff
            i += 1
            repeat(n) {
                if (i + 2 > d.size) return
                val tam = ((d[i].toInt() and 0xff) shl 8) or (d[i + 1].toInt() and 0xff)
                i += 2
                if (tam <= 0 || i + tam > d.size) return
                if (novoPps == null) novoPps = d.copyOfRange(i, i + tam)
                i += tam
            }
            val s = novoSps ?: return
            val p = novoPps ?: return
            synchronized(trava) {
                val mudou = !s.contentEquals(sps) || !p.contentEquals(pps)
                sps = s
                pps = p
                if (mudou || codec == null) {
                    pararCodec()
                    fila.clear()
                    if (superficie != null) iniciarCodec()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "cabeçalho de sequência ilegível: ${e.message}")
        }
    }

    /** AVCC (NALUs com prefixo de tamanho) → Annex-B (00 00 00 01). */
    private fun paraAnnexB(d: ByteArray): ByteArray? {
        val saida = ByteArrayOutputStream(d.size + 16)
        var i = 5
        var achou = false
        while (i + prefixo <= d.size) {
            var tam = 0
            for (k in 0 until prefixo) tam = (tam shl 8) or (d[i + k].toInt() and 0xff)
            i += prefixo
            if (tam <= 0 || i + tam > d.size) break
            saida.write(PARTIDA)
            saida.write(d, i, tam)
            i += tam
            achou = true
        }
        return if (achou) saida.toByteArray() else null
    }

    // ------------------------------------------------------------------------
    // MediaCodec
    // ------------------------------------------------------------------------

    private fun iniciarCodec() {
        val s = superficie ?: return
        val umSps = sps ?: return
        val umPps = pps ?: return
        try {
            val formato = MediaFormat.createVideoFormat(MIME, LARGURA_NOMINAL, ALTURA_NOMINAL)
            // csd-0/csd-1 em Annex-B: o decodificador tira daí a resolução real
            // e corrige o formato nominal acima no INFO_OUTPUT_FORMAT_CHANGED.
            formato.setByteBuffer("csd-0", ByteBuffer.wrap(PARTIDA + umSps))
            formato.setByteBuffer("csd-1", ByteBuffer.wrap(PARTIDA + umPps))
            val c = MediaCodec.createDecoderByType(MIME)
            c.configure(formato, s, null, 0)
            c.start()
            codec = c
            esperandoKeyframe = true
            rodando = true
            trabalhador = Thread({ laco(c) }, "PV-Decoder").apply { isDaemon = true; start() }
            publicar { it.copy(decodificando = true, erro = null) }
            Log.i(TAG, "decodificador de vídeo iniciado")
        } catch (e: Exception) {
            codec = null
            rodando = false
            Log.w(TAG, "não consegui iniciar o decodificador: ${e.message}")
            publicar { it.copy(decodificando = false, erro = "decodificador indisponível: ${e.message}") }
        }
    }

    private fun pararCodec() {
        rodando = false
        trabalhador?.interrupt()
        trabalhador = null
        val c = codec
        codec = null
        if (c != null) {
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        esperandoKeyframe = true
    }

    private fun laco(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (rodando) {
            try {
                val p = fila.poll(80, TimeUnit.MILLISECONDS)
                if (p != null) {
                    val indice = c.dequeueInputBuffer(20_000)
                    if (indice >= 0) {
                        val buf = c.getInputBuffer(indice)
                        if (buf != null) {
                            buf.clear()
                            buf.put(p.annexB)
                            c.queueInputBuffer(
                                indice, 0, p.annexB.size, p.ptsUs,
                                if (p.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
                            )
                        } else {
                            descartar()
                        }
                    } else {
                        // Sem buffer de entrada livre: o quadro se perde, e é o
                        // certo — segurar aqui atrasaria o vídeo ao vivo.
                        descartar()
                    }
                }
                // Drena tudo o que estiver pronto e MANDA PARA A TELA (render=true).
                while (rodando) {
                    val saida = c.dequeueOutputBuffer(info, 0)
                    when {
                        saida >= 0 -> {
                            c.releaseOutputBuffer(saida, true)
                            publicar { it.copy(quadrosNaTela = it.quadrosNaTela + 1) }
                        }
                        saida == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = c.outputFormat
                            val l = runCatching { f.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(0)
                            val a = runCatching { f.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(0)
                            publicar { it.copy(largura = l, altura = a) }
                            Log.i(TAG, "resolução do vídeo dos óculos: ${l}x$a")
                        }
                        else -> break
                    }
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: IllegalStateException) {
                // Codec morreu (aparelho dormiu, superfície sumiu): reinicia na
                // próxima configuração/keyframe em vez de ficar preto para sempre.
                Log.w(TAG, "decodificador em estado inválido: ${e.message}")
                publicar { it.copy(decodificando = false, erro = "decodificador reiniciando") }
                synchronized(trava) {
                    pararCodec()
                    if (superficie != null && sps != null) iniciarCodec()
                }
                return
            } catch (e: Exception) {
                Log.w(TAG, "decodificação falhou: ${e.message}")
                publicar { it.copy(erro = e.message) }
                return
            }
        }
    }

    fun encerrar() {
        synchronized(trava) {
            pararCodec()
            fila.clear()
            superficie = null
            sps = null
            pps = null
            publicar { Estado() }
        }
    }

    private fun descartar() {
        publicar { it.copy(quadrosDescartados = it.quadrosDescartados + 1) }
    }

    /** Uma publicação a cada ~10 quadros: avisar a UI 30 vezes por segundo só
     *  gasta bateria e recomposição. */
    private fun publicar(f: (Estado) -> Estado) {
        val antigo = estado
        val novo = f(antigo)
        estado = novo
        val mudouAlgoQueImporta = novo.decodificando != antigo.decodificando ||
            novo.erro != antigo.erro ||
            novo.largura != antigo.largura
        if (mudouAlgoQueImporta || novo.quadrosNaTela % 10 == 0) aoMudar?.invoke(novo)
    }

    companion object {
        const val TAG = "PV-Preview"
        private const val MIME = "video/avc"
        /** Corrigido pelo próprio decodificador quando o SPS é lido. */
        private const val LARGURA_NOMINAL = 1280
        private const val ALTURA_NOMINAL = 720
        private const val FILA = 12
        private val PARTIDA = byteArrayOf(0, 0, 0, 1)
    }
}
