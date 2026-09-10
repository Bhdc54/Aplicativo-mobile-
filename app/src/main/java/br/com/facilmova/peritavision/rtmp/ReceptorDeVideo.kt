// O tablet como destino do vídeo dos óculos (ver RtmpIngest.kt).
//
// Este arquivo é a parte que conhece Android: descobre o IP do tablet na
// Wi-Fi, sobe o RtmpIngest numa porta, guarda os segmentos em
// filesDir/video/<sessão>/ e resume o que está chegando num estado simples
// para o cartão "Visão dos óculos" (quadros/s, tamanho, segmentos).
package br.com.facilmova.peritavision.rtmp

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface

class ReceptorDeVideo(context: Context) {
    private val appContext = context.applicationContext

    /** Onde os .flv ficam até subirem ao servidor. */
    val pasta: File = File(appContext.filesDir, "video")

    data class Estado(
        val ligado: Boolean = false,
        val ip: String? = null,
        val porta: Int = PORTA_PADRAO,
        val publicando: Boolean = false,
        val chave: String? = null,
        val quadros: Int = 0,
        val bytes: Long = 0,
        val quadrosPorSegundo: Double = 0.0,
        val segmentosFechados: Int = 0,
        val ultimoQuadroMs: Long = 0,
        val ultimoMotivo: String? = null,
        /** Último endereço que abriu TCP na porta (os óculos, se a rede permitir). */
        val ultimoCliente: String? = null,
        val erro: String? = null,
    )

    @Volatile var estado = Estado()
        private set

    /** Chamado (em thread de rede) a cada mudança de estado que interessa à tela. */
    var aoMudar: ((Estado) -> Unit)? = null
    /** Um segmento fechou e está pronto para subir. */
    var aoSegmentoFechado: ((chave: String, arquivo: File) -> Unit)? = null
    /** Quadro de vídeo bruto (payload FLV/AVC) — para o preview e para a IA, mais adiante. */
    var aoQuadro: ((RtmpIngest.Evento.Quadro) -> Unit)? = null

    private var servidor: RtmpIngest? = null
    private val instantesDeQuadro = ArrayDeque<Long>()
    private var bytesDoSegmento = 0L

    /** Sobe o servidor. Devolve o erro em texto, ou null se subiu. */
    fun ligar(porta: Int = PORTA_PADRAO): String? {
        if (servidor != null) return null
        val ip = ipNaWifi()
        if (ip == null) {
            estado = Estado(ligado = false, erro = "tablet sem IP na Wi-Fi — conecte o tablet na mesma rede dos óculos")
            aoMudar?.invoke(estado)
            return estado.erro
        }
        pasta.mkdirs()
        val s = RtmpIngest(porta, pasta, ::tratar)
        return try {
            s.iniciar()
            servidor = s
            estado = Estado(ligado = true, ip = ip, porta = s.portaEmUso)
            aoMudar?.invoke(estado)
            null
        } catch (e: IOException) {
            val msg = "porta $porta ocupada ou bloqueada: ${e.message}"
            estado = Estado(ligado = false, ip = ip, erro = msg)
            aoMudar?.invoke(estado)
            msg
        }
    }

    fun desligar() {
        servidor?.encerrar()
        servidor = null
        estado = Estado()
        aoMudar?.invoke(estado)
    }

    /** URL que vai para os óculos por BLE. */
    fun urlPara(sessaoId: String): String? {
        val e = estado
        if (!e.ligado || e.ip == null) return null
        return "rtmp://${e.ip}:${e.porta}/pv/$sessaoId"
    }

    /** Segmentos gravados da sessão, em ordem cronológica (nome = unix-ms). */
    fun segmentosDe(sessaoId: String): List<File> =
        File(pasta, sessaoId).listFiles { f -> f.isFile && f.name.endsWith(".flv") }
            ?.sortedBy { it.name.removeSuffix(".flv").toLongOrNull() ?: it.lastModified() }
            ?: emptyList()

    private fun tratar(ev: RtmpIngest.Evento) {
        when (ev) {
            is RtmpIngest.Evento.Conectou -> {
                Log.i(TAG, "cliente conectou na porta: ${ev.de}")
                estado = estado.copy(ultimoCliente = ev.de, erro = null)
                aoMudar?.invoke(estado)
            }
            is RtmpIngest.Evento.Publicando -> {
                instantesDeQuadro.clear(); bytesDoSegmento = 0
                estado = estado.copy(publicando = true, chave = ev.chave, quadros = 0, bytes = 0, quadrosPorSegundo = 0.0, erro = null)
                Log.i(TAG, "óculos publicando ${ev.chave} → ${ev.arquivo}")
                aoMudar?.invoke(estado)
            }
            is RtmpIngest.Evento.Quadro -> {
                if (!ev.cabecalhoDeSequencia) {
                    val agora = System.currentTimeMillis()
                    instantesDeQuadro.addLast(agora)
                    while (instantesDeQuadro.isNotEmpty() && agora - instantesDeQuadro.first() > 2_000) instantesDeQuadro.removeFirst()
                    bytesDoSegmento += ev.dados.size
                    val fps = instantesDeQuadro.size / 2.0
                    val q = estado.quadros + 1
                    // Atualiza a tela ~2x por segundo, não a cada quadro.
                    if (q % 8 == 0 || q == 1) {
                        estado = estado.copy(quadros = q, bytes = bytesDoSegmento, quadrosPorSegundo = fps, ultimoQuadroMs = agora)
                        aoMudar?.invoke(estado)
                    } else estado = estado.copy(quadros = q, bytes = bytesDoSegmento, ultimoQuadroMs = agora)
                }
                aoQuadro?.invoke(ev)
            }
            is RtmpIngest.Evento.Encerrado -> {
                estado = estado.copy(publicando = false, segmentosFechados = estado.segmentosFechados + 1,
                    quadrosPorSegundo = 0.0, ultimoMotivo = ev.motivo, bytes = ev.bytes, quadros = ev.quadros)
                Log.i(TAG, "segmento fechado ${ev.arquivo.name}: ${ev.bytes} bytes, ${ev.quadros} quadros, ${ev.duracaoMs} ms (${ev.motivo})")
                aoMudar?.invoke(estado)
                if (ev.bytes > 13) aoSegmentoFechado?.invoke(ev.chave, ev.arquivo) else ev.arquivo.delete() // só cabeçalho: lixo
            }
            is RtmpIngest.Evento.Erro -> {
                Log.w(TAG, "receptor: ${ev.mensagem}")
                estado = estado.copy(erro = ev.mensagem)
                aoMudar?.invoke(estado)
            }
        }
    }

    /** IPv4 do tablet na rede Wi-Fi. Primeiro pelo ConnectivityManager (a rede
     *  ativa com transporte Wi-Fi); se não der, varre as interfaces wlan.
     *  Público porque o receptor de FOTOS precisa do mesmo endereço. */
    fun ipNaWifi(): String? {
        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            for (rede in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(rede) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val lp = cm.getLinkProperties(rede) ?: continue
                lp.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
                    ?.let { return it.address.hostAddress }
            }
        } catch (e: Exception) { Log.w(TAG, "ConnectivityManager: ${e.message}") }
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                if (!iface.name.startsWith("wlan") && !iface.name.startsWith("ap") && !iface.name.startsWith("swlan")) continue
                for (a in iface.inetAddresses) if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress
            }
        } catch (e: Exception) { Log.w(TAG, "interfaces: ${e.message}") }
        return null
    }

    companion object {
        const val TAG = "PV-Receptor"
        const val PORTA_PADRAO = 1935
    }
}
