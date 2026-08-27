package com.example.peritavision

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import com.example.peritavision.scan.LeitorCodigo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.peritavision.audio.FeedbackDeVoz
import com.example.peritavision.device.GlassesDevice
import com.example.peritavision.device.GlassesDeviceFactory
import com.example.peritavision.device.GlassesEvent
import com.example.peritavision.device.MentraGlassesDevice
import com.example.peritavision.device.PhoneGlassesDevice
import com.example.peritavision.domain.CofreCustodia
import com.example.peritavision.domain.Evidencia
import com.example.peritavision.domain.SelarCustodia
import com.example.peritavision.domain.TipoEvidencia
import com.example.peritavision.net.AudioStreamer
import com.example.peritavision.net.PonteGemini
import com.example.peritavision.net.BackendClient
import com.example.peritavision.ui.AvisoEscuta
import com.example.peritavision.ui.BarraDeStatus
import com.example.peritavision.ui.BarraDeTopo
import com.example.peritavision.ui.BotaoContorno
import com.example.peritavision.ui.BotaoPrimario
import com.example.peritavision.ui.BotaoTonal
import com.example.peritavision.ui.CabecalhoCartao
import com.example.peritavision.ui.CampoPv
import com.example.peritavision.ui.CartaoPv
import com.example.peritavision.ui.CartaoRecolhivel
import com.example.peritavision.ui.Contador
import com.example.peritavision.ui.Etiqueta
import com.example.peritavision.ui.FaixaProntidao
import com.example.peritavision.ui.LinhaDado
import com.example.peritavision.ui.PeritavisionTheme
import com.example.peritavision.ui.Prontidao
import com.example.peritavision.ui.PvTheme
import com.example.peritavision.ui.RodapeMarca
import com.example.peritavision.ui.TextoApoio
import com.example.peritavision.ui.Tom
import com.example.peritavision.voice.VoiceTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Marca ───────────────────────────────────────────────────────────────────
// A logo em si é um arquivo: res/drawable/logo.xml. Para trocar pela logo real,
// leia as instruções que estão dentro daquele arquivo — não precisa vir aqui.
private const val NOME_EMPRESA = "Facilmova"
private const val SLOGAN_APP = "Perícia assistida · POLITEC-MT"

// Hardware alvo: Mentra Live (BLE pelo proprio app).
// Troque para PHONE para testar sem oculos (usa a camera do celular).
private val TIPO_DISPOSITIVO = GlassesDeviceFactory.Tipo.MENTRA

// ── Backend ─────────────────────────────────────────────────────────────────
// Servidor em nuvem: funciona de qualquer rede (Wi-Fi ou 4G).
private val BACKEND_PADRAO = BuildConfig.PV_BACKEND
// Conta de DISPOSITIVO: o app autentica sozinho; a identidade do perito
// fica no site. Em producao vira provisionamento por aparelho.
// Credenciais de dev vem do local.properties via BuildConfig — nada de
// senha no codigo-fonte (este arquivo vai para o git).
private val MATRICULA_PADRAO = BuildConfig.PV_MATRICULA
private val SENHA_PADRAO = BuildConfig.PV_SENHA
private val PROTOCOLO_PADRAO = BuildConfig.PV_PROTOCOLO

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Desenha de ponta a ponta (obrigatório a partir do targetSdk 35). O
        // cabeçalho e a barra de status respeitam os insets mais abaixo.
        // Estilo FIXO em claro: o app agora e sempre tema claro (Theme.kt), mas
        // enableEdgeToEdge() sem argumento segue o modo escuro do APARELHO — com
        // o tablet em modo escuro, os icones da barra de status ficariam brancos
        // sobre o fundo branco do app. Fixar o estilo claro = icones escuros.
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = androidx.activity.SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        setContent {
            PeritavisionTheme {
                CaptureScreen()
            }
        }
    }
}

/**
 * Onde a tela está, no fluxo do perito. Serve para uma coisa só: decidir qual
 * cartão sobe para o topo e recebe destaque.
 */
private enum class Passo { CONECTAR, SESSAO, CAPTURAR }


@Composable
fun CaptureScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Camada de dominio/custodia (device-agnostica) + device escolhido.
    val cofre = remember { CofreCustodia(context) }
    val selar = remember { SelarCustodia(cofre) }
    // Confirmação FALADA: "Foto capturada" etc. Sai pelo alto-falante dos
    // óculos se eles estiverem pareados como áudio Bluetooth; senão, celular.
    val vozFeedback = remember { FeedbackDeVoz(context) }
    val device: GlassesDevice = remember {
        GlassesDeviceFactory.create(context, cofre.diretorioEvidencias, TIPO_DISPOSITIVO)
    }
    val ehMentra = device is MentraGlassesDevice

    // Permissoes: camera, microfone e Bluetooth (para o Mentra).
    // Localizacao NAO entra mais: o GPS saiu da cadeia de custodia. A unica
    val permsNecessarias = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    var temPermissoes by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> temPermissoes = result.values.any { it } || temPermissoes }

    LaunchedEffect(Unit) {
        val faltando = permsNecessarias.any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (faltando) permissionLauncher.launch(permsNecessarias.toTypedArray())
        else temPermissoes = true
    }

    // Estado de tela.
    var status by remember { mutableStateOf("Pronto") }
    var conectado by remember { mutableStateOf(false) }
    var conectando by remember { mutableStateOf(false) }
    var gravandoAudio by remember { mutableStateOf(false) }
    var ultima by remember { mutableStateOf<Evidencia?>(null) }
    LaunchedEffect(vozFeedback) { vozFeedback.onStatus = { msg -> status = msg } }

    // ── Comando de voz ────────────────────────────────────────────────────
    // Declarado ANTES do coletor de eventos porque ele precisa acionar a voz
    // (uma funcao/variavel local so e visivel abaixo de onde foi declarada).
    val voz = remember {
        VoiceTrigger(context, onComando = { vozFeedback.falar("Capturando"); device.capturarFoto() })
    }
    var vozAtiva by remember { mutableStateOf(false) }
    /** true depois que os oculos falharem em transcrever: escuta pelo celular. */
    var usarVozDoCelular by remember { mutableStateOf(false) }
    /** Canal que leva o audio DOS OCULOS ao servico de voz do backend. */
    var audioStreamer by remember { mutableStateOf<AudioStreamer?>(null) }

    // ── Assistente IA de bancada (ponte Gemini Live) — AUTOMÁTICO na sessão.
    // Liga sozinho quando a sessão de perícia abre e desliga quando ela fecha
    // (ver o LaunchedEffect logo depois da declaração de sessaoId). O toque no
    // cartão vira o desliga/religa manual do perito. Com a ponte ativa, uma
    // CÓPIA do áudio dos óculos vai ao Gemini (aviso de custódia em
    // PonteGemini.kt); o caminho offline oficial continua intacto em paralelo.
    var ponteGemini by remember { mutableStateOf<PonteGemini?>(null) }
    var iaPerito by remember { mutableStateOf("") }
    var iaResposta by remember { mutableStateOf("") }
    LaunchedEffect(voz) { voz.onStatus = { msg -> status = msg } }

    // ── Backend: endereco editavel, cliente e sessao aberta ────────────────
    val escopo = rememberCoroutineScope()
    var enderecoBackend by remember { mutableStateOf(BACKEND_PADRAO) }
    val backend = remember { BackendClient(BACKEND_PADRAO) }
    var sessaoId by remember { mutableStateOf<String?>(null) }
    var laudoId by remember { mutableStateOf<String?>(null) }
    var ocupado by remember { mutableStateOf(false) }
    var fotosEnviadas by remember { mutableIntStateOf(0) }

    // Declaradas aqui (e não junto dos estados da IA, mais acima) porque usam
    // sessaoId — em Kotlin, função local não enxerga variável declarada abaixo.
    fun ligarAssistenteIa() {
        if (ponteGemini != null) return
        if (BuildConfig.PV_PONTE_URL.isBlank()) {
            status = "Assistente IA: configure pv.ponte no local.properties."
            return
        }
        val ponte = PonteGemini(BuildConfig.PV_PONTE_URL, sessaoId ?: "bancada-teste")
        ponte.onStatus = { msg -> status = msg }
        ponte.onTranscricao = { t -> iaPerito = t }
        ponte.onResposta = { t -> iaResposta = t }
        ponte.conectar()
        ponteGemini = ponte
        status = "Assistente IA conectando..."
    }
    fun alternarAssistenteIa() {
        val ativa = ponteGemini
        if (ativa != null) {
            ativa.encerrar()
            ponteGemini = null
            status = "Assistente IA desligado."
            return
        }
        ligarAssistenteIa()
    }
    // AUTOMÁTICO: sessão abriu → assistente liga sozinho; sessão fechou →
    // desliga junto. Se o perito desligar à mão no meio da sessão, fica
    // desligado até a próxima sessão (nada religa antes disso) — o toque
    // dele manda mais que o automatismo.
    LaunchedEffect(sessaoId) {
        if (sessaoId != null) {
            ligarAssistenteIa()
        } else {
            ponteGemini?.encerrar()
            ponteGemini = null
            iaPerito = ""
            iaResposta = ""
        }
    }

    // Wi-Fi DOS OCULOS: o JPEG sobe pela rede do oculos, nao pelo Bluetooth.
    var wifiOculos by remember { mutableStateOf(false) }
    var ssidOculos by remember { mutableStateOf<String?>(null) }
    var wifiSsid by remember { mutableStateOf("") }
    var wifiSenha by remember { mutableStateOf("") }
    var matricula by remember { mutableStateOf(MATRICULA_PADRAO) }
    var senhaPerito by remember { mutableStateOf(SENHA_PADRAO) }
    var protocolo by remember { mutableStateOf(PROTOCOLO_PADRAO) }
    var rtmpUrl by remember { mutableStateOf<String?>(null) }
    var videoLigado by remember { mutableStateOf(false) }

    // "Visão dos óculos": o mesmo stream RTMP que os óculos mandam ao backend,
    // devolvido como HTTP-FLV pelo node-media-server (porta RTMP_HTTP_PORT=8001
    // no .env do backend). rtmp://host:1935/live/ID → http://host:8001/live/ID.flv
    val urlVisao = rtmpUrl?.let { r ->
        Regex("^rtmp://([^:/]+)(?::\\d+)?/(.+)$").find(r)?.let { m ->
            "http://${m.groupValues[1]}:8001/${m.groupValues[2]}.flv"
        }
    }

    // Laudo sendo escrito: depois do finalizar, o backend monta o laudo seção a
    // seção. Este poll leve (a cada 2s, por ~30s) faz as seções APARECEREM na
    // tela conforme ficam prontas, em vez de mandar o perito "ver no site".
    var trechosLaudo by remember { mutableStateOf<List<BackendClient.TrechoLaudo>>(emptyList()) }
    LaunchedEffect(laudoId) {
        trechosLaudo = emptyList()
        val id = laudoId ?: return@LaunchedEffect
        repeat(15) {
            runCatching { trechosLaudo = backend.obterLaudo(id) }
            kotlinx.coroutines.delay(2_000)
        }
    }

    // ── Leitura de lacre PELOS ÓCULOS ─────────────────────────────────────
    // O perito toca no botão, os óculos fotografam o código de barras, o
    // servidor decodifica e consulta o Atena; a ficha do caso aparece na tela.
    var fichaLacre by remember { mutableStateOf<BackendClient.FichaLacre?>(null) }
    var lendoLacre by remember { mutableStateOf(false) }
    fun lerLacrePelosOculos() {
        val mentra = device as? MentraGlassesDevice
        if (mentra == null || !conectado) {
            status = "Conecte os óculos antes de ler o lacre."
            return
        }
        if (lendoLacre) return
        escopo.launch {
            lendoLacre = true
            try {
                backend.baseUrl = enderecoBackend.trim()
                backend.login(matricula.trim(), senhaPerito.trim())
                status = "Aponte os óculos para o lacre..."
                val cred = backend.solicitarLeituraLacre()
                mentra.capturarFotoComAutorizacao(
                    MentraGlassesDevice.AutorizacaoCaptura(cred.requestId, cred.webhookUrl, cred.authToken)
                )
                // Poll: a foto sobe pelo Wi-Fi dos óculos e o servidor decodifica.
                var ficha: BackendClient.FichaLacre? = null
                repeat(20) {
                    if (ficha == null) {
                        kotlinx.coroutines.delay(1_500)
                        ficha = backend.obterLeituraLacre(cred.requestId)
                    }
                }
                if (ficha == null) {
                    status = "Leitura do lacre expirou — a foto chegou ao servidor? Confira o Wi-Fi dos óculos."
                } else {
                    fichaLacre = ficha
                    protocolo = ficha!!.numeroProtocolo
                    vozFeedback.falar("Lacre lido. Protocolo ${ficha!!.numeroProtocolo}.")
                    status = "Lacre ${ficha!!.codigo} → protocolo ${ficha!!.numeroProtocolo}"
                }
            } catch (e: Exception) {
                status = "Leitura do lacre: ${e.message}"
            } finally {
                lendoLacre = false
            }
        }
    }

    /** Login -> resolve o protocolo -> abre a sessao. Um botao so. */
    fun iniciarSessao() {
        if (ocupado) return
        escopo.launch {
            ocupado = true
            try {
                backend.baseUrl = enderecoBackend.trim()
                status = "Entrando no backend..."
                backend.login(matricula.trim(), senhaPerito.trim())
                status = "Resolvendo protocolo ${protocolo.trim()}..."
                val casoId = backend.resolverProtocolo(protocolo.trim())
                val perfilId = backend.primeiroPerfil()
                status = "Abrindo sessão..."
                val aberta = backend.abrirSessao(casoId, perfilId)
                sessaoId = aberta.sessaoId
                rtmpUrl = aberta.rtmpUrl
                vozFeedback.falar("Sessão iniciada. Pode capturar.")
                laudoId = null
                fotosEnviadas = 0
                status = "Sessão aberta — pode capturar"
            } catch (e: Exception) {
                status = "Erro no backend: ${e.message}"
            } finally {
                ocupado = false
            }
        }
    }

    /** Fecha a sessao; o backend monta o laudo. */
    fun finalizarSessao() {
        val id = sessaoId ?: return
        if (ocupado) return
        escopo.launch {
            ocupado = true
            try {
                if (videoLigado) {
                    status = "Encerrando o vídeo dos óculos..."
                    device.pararVideo()
                    videoLigado = false
                    kotlinx.coroutines.delay(1500)
                }
                status = "Finalizando sessão..."
                laudoId = backend.finalizarSessao(id)
                sessaoId = null
                rtmpUrl = null
                vozFeedback.falar("Sessão finalizada. Laudo em processamento.")
                status = "Laudo gerado — revise e baixe no site do PeritaVision"
            } catch (e: Exception) {
                status = "Erro ao finalizar: ${e.message}"
            } finally {
                ocupado = false
            }
        }
    }

    // Injeta no Mentra COMO pedir autorizacao de captura ao backend. Sem isso,
    // falar "capturar" nao faz nada (era exatamente o sintoma anterior).
    LaunchedEffect(device) {
        (device as? MentraGlassesDevice)?.obterAutorizacao = {
            sessaoId?.let { id ->
                val c = backend.solicitarCaptura(id)
                MentraGlassesDevice.AutorizacaoCaptura(c.requestId, c.webhookUrl, c.authToken)
            }
        }
    }

    // Escuta os eventos do device e sela a custodia de cada arquivo capturado.
    LaunchedEffect(device) {
        device.eventos.collect { evento ->
            when (evento) {
                is GlassesEvent.Conexao -> {
                    conectado = evento.conectado
                    conectando = false
                    status = if (evento.conectado) "Óculos conectado" else "Óculos desconectado"
                }
                is GlassesEvent.Wifi -> {
                    wifiOculos = evento.conectado
                    ssidOculos = evento.ssid
                    status = if (evento.conectado)
                        "Óculos na Wi-Fi ${evento.ssid ?: ""} ✓"
                    else "Óculos SEM Wi-Fi — a foto não chega ao servidor"
                }
                is GlassesEvent.GravacaoIniciada ->
                    status = "Gravando ${evento.tipo.name.lowercase()}..."
                is GlassesEvent.Erro -> {
                    conectando = false
                    status = "Erro: ${evento.mensagem}"
                }
                is GlassesEvent.Aviso -> status = evento.mensagem
                is GlassesEvent.TranscricaoIndisponivel -> {
                    // Os oculos ouvem mas nao transcrevem: assume o microfone
                    // do celular, sem o perito precisar fazer nada.
                    usarVozDoCelular = true
                    voz.iniciar()
                    vozAtiva = true
                }
                is GlassesEvent.CapturaRemota -> {
                    // Os oculos subiram o JPEG direto ao webhook; o backend selou.
                    // So agora, com a confirmacao real (onPhotoResponse), falamos
                    // "foto capturada" — falar antes disso poderia mentir sobre
                    // uma captura que na verdade falhou.
                    fotosEnviadas++
                    status = "Foto $fotosEnviadas enviada ao backend ✓"
                    // Pede a descrição EM VOZ, pelos óculos (se pareados como
                    // áudio Bluetooth; senão, pelo celular). A próxima fala do
                    // perito vira a legenda desta foto — o backend ancora o
                    // primeiro trecho posterior ao pedido da captura.
                    vozFeedback.falar("Foto capturada. Descreva a evidência.")
                }
                is GlassesEvent.ArquivoCapturado -> {
                    // Modo PHONE: o arquivo esta no celular. Sela localmente e,
                    // se houver sessao aberta, sobe para o backend tambem.
                    status = "Selando custódia..."
                    val ev = withContext(Dispatchers.IO) {
                        selar.selar(evento.tipo, evento.arquivo, protocolo.trim().ifBlank { null })
                    }
                    ultima = ev
                    status = "Evidência selada (${ev.tipo.name})"
                    if (evento.tipo == TipoEvidencia.FOTO) {
                        vozFeedback.falar("Foto capturada. Descreva a evidência.")
                    }

                    val id = sessaoId
                    if (id != null && evento.tipo == TipoEvidencia.FOTO) {
                        status = "Enviando foto ao backend..."
                        try {
                            val cred = backend.solicitarCaptura(id)
                            backend.enviarFoto(cred, evento.arquivo)
                            fotosEnviadas++
                            status = "Foto $fotosEnviadas enviada ao backend ✓"
                        } catch (e: Exception) {
                            status = "Falha ao enviar ao backend: ${e.message}"
                            vozFeedback.falar("Falha ao enviar ao servidor")
                        }
                    }
                }
            }
        }
    }

    // Comando de voz pelos MICROFONES DOS OCULOS (transcricao local, sem nuvem).
    LaunchedEffect(device) {
        (device as? MentraGlassesDevice)?.onComandoVoz = { device.capturarFoto() }
    }

    /**
     * Liga/desliga a escuta. Prefere o microfone DOS OCULOS (caminho oficial:
     * 3 mics, o perito nao precisa segurar o celular). Se os oculos ja tiverem
     */
    fun alternarComandoDeVoz() {
        val mentra = device as? MentraGlassesDevice
        val usarOculos = mentra != null && conectado && !usarVozDoCelular
        if (vozAtiva) {
            if (usarOculos) mentra.pararComandoDeVoz() else voz.encerrar()
            vozAtiva = false
            status = "Comando de voz desligado"
        } else {
            if (usarOculos) mentra.iniciarComandoDeVoz() else voz.iniciar()
            vozAtiva = true
        }
    }

    /**
     * Escuta CONTINUA enquanto a sessao estiver aberta.
     * O microfone fica ligado do inicio ao fim da sessao, mas o app so AGE ao
     */
    LaunchedEffect(sessaoId, conectado, usarVozDoCelular) {
        val mentra = device as? MentraGlassesDevice
        val deveEscutar = sessaoId != null && (mentra == null || conectado)
        val pelosOculos = mentra != null && !usarVozDoCelular
        if (deveEscutar && !vozAtiva) {
            if (pelosOculos) mentra.iniciarComandoDeVoz() else voz.iniciar()
            vozAtiva = true
        } else if (!deveEscutar && vozAtiva) {
            if (pelosOculos) mentra?.pararComandoDeVoz() else voz.encerrar()
            vozAtiva = false
        }
    }

    /**
     * ÁUDIO DOS ÓCULOS → BACKEND → COMANDO.
     * Este é o caminho oficial da arquitetura. Os óculos entregam PCM por
     */
    // Liga o vídeo dos óculos assim que a sessão abre e os óculos estão prontos.
    LaunchedEffect(sessaoId, conectado, rtmpUrl) {
        val url = rtmpUrl
        if (sessaoId != null && !videoLigado && url != null &&
            (device !is MentraGlassesDevice || conectado)
        ) {
            device.iniciarVideo(url)
            videoLigado = true
        }
    }

    LaunchedEffect(sessaoId, conectado) {
        val mentra = device as? MentraGlassesDevice
        val id = sessaoId
        val jwt = backend.token
        if (mentra == null || id == null || jwt == null || !conectado) {
            audioStreamer?.encerrar()
            audioStreamer = null
            mentra?.onPcm = null
            return@LaunchedEffect
        }
        val streamer = AudioStreamer(enderecoBackend.trim(), jwt, id).apply {
            onStatus = { msg -> status = msg }
            onComando = { intencao, ouvido ->
                status = "Comando \"$ouvido\" → $intencao"
                when (intencao) {
                    "CAPTURAR" -> { vozFeedback.falar("Capturando"); device.capturarFoto() }
                    "FINALIZAR" -> finalizarSessao()
                    // MARCAR e DESCARTAR entram junto com a narração do laudo.
                }
            }
        }
        streamer.conectar()
        audioStreamer = streamer
        // Cada frame do microfone dos óculos segue direto para o servidor.
        // Caminho oficial (offline) + cópia opcional para o assistente IA.
        mentra.onPcm = { pcm, _ ->
            streamer.enviarPcm(pcm)
            ponteGemini?.enviarPcm(pcm)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            ponteGemini?.encerrar()
            voz.encerrar()
            vozFeedback.encerrar()
            audioStreamer?.encerrar()
            (device as? MentraGlassesDevice)?.let { it.onPcm = null; it.pararComandoDeVoz() }
            device.encerrar()
        }
    }

    // So libera a captura com o hardware pronto E uma sessao aberta no backend
    // (a foto precisa de um token de captura emitido pelo servidor).
    val hardwarePronto = if (ehMentra) conectado else temPermissoes
    val podeCapturar = hardwarePronto && sessaoId != null

    // Acao do botao "Conectar oculos" (tambem chamada sozinha ao abrir a tela).
    fun conectarOculos() {
        when (val d = device) {
            is MentraGlassesDevice -> {
                conectando = true
                status = "Procurando óculos..."
                // O SDK da Mentra LANÇA exceção se o Bluetooth do aparelho
                // estiver desligado ("Turn on phone Bluetooth to scan..."). Sem
                // este catch a exceção subia pela coroutine e DERRUBAVA o app —
                // era crash, não aviso. Bluetooth desligado é situação normal de
                // bancada; o app avisa e espera, não morre.
                try {
                    d.conectar()
                } catch (e: Exception) {
                    conectando = false
                    status = "Ligue o Bluetooth do aparelho e toque em CONECTAR ÓCULOS."
                }
            }
            else -> { conectado = true } // PhoneGlassesDevice: nao precisa parear
        }
    }

    // Conecta SOZINHO assim que a tela abre e as permissões estão prontas —
    // não precisa mais tocar em "CONECTAR ÓCULOS" toda vez que abrir o app.
    // Só tenta uma vez por abertura de tela (LaunchedEffect(temPermissoes) não
    // reexecuta à toa; o retry automático de rede já mora no MentraGlassesDevice).
    LaunchedEffect(temPermissoes) {
        if (temPermissoes && ehMentra && !conectado && !conectando) conectarOculos()
    }

    // ══════════════════════════════════════════════════════════════════════
    //  A PARTIR DAQUI É SÓ APRESENTAÇÃO. Nenhuma regra de negócio abaixo.
    // ══════════════════════════════════════════════════════════════════════

    val temSessao = sessaoId != null
    val ouvindoPelosOculos = vozAtiva && ehMentra && conectado && !usarVozDoCelular

    // Passo atual: define qual cartão sobe ao topo e ganha borda de destaque.
    val passo = when {
        ehMentra && !conectado -> Passo.CONECTAR
        !temSessao -> Passo.SESSAO
        else -> Passo.CAPTURAR
    }

    // Por que a captura não está liberada — dito na tela, não deixado para o
    // perito adivinhar olhando um botão cinza.
    val motivoBloqueio = when {
        podeCapturar -> null
        ehMentra && !conectado -> "Conecte os óculos para liberar a captura."
        !hardwarePronto -> "Conceda as permissões de câmera e microfone."
        else -> "Abra uma sessão no cartão Servidor — a foto precisa de um token emitido pelo servidor."
    }

    // Tom da barra de status. Derivado da própria mensagem, de propósito: um
    // segundo estado "tomDoStatus" para manter em sincronia com o texto seria
    // uma fonte de bug silencioso a cada mensagem nova.
    val tomStatus = when {
        status.startsWith("Erro") || status.startsWith("Falha") || status.contains("SEM Wi-Fi") -> Tom.ERRO
        status.contains("✓") || status.contains("selada") || status.contains("aberta") ||
            status.contains("conectado") || status.contains("Laudo gerado") -> Tom.OK
        status.endsWith("...") -> Tom.ATENCAO
        else -> Tom.NEUTRO
    }

    val prontidao = buildList {
        if (ehMentra) {
            add(
                Prontidao(
                    "Óculos",
                    when {
                        conectado -> Tom.OK
                        conectando -> Tom.ATENCAO
                        else -> Tom.ERRO
                    }
                )
            )
            add(Prontidao("Wi-Fi", if (wifiOculos) Tom.OK else Tom.ERRO))
        } else {
            add(Prontidao("Câmera", if (temPermissoes) Tom.OK else Tom.ERRO))
        }
        add(Prontidao("Sessão", if (temSessao) Tom.OK else Tom.ERRO))
    }

    // Modo de teste (PHONE): a pré-visualização da câmera do celular entra DENTRO
    // do cartão de captura, em vez de ser o fundo da tela inteira. Antes ela
    // ficava atrás dos cartões, ou seja, invisível justo quando servia de plano B.
    val phone = device as? PhoneGlassesDevice
    val previewCamera: (@Composable () -> Unit)? =
        if (phone != null && temPermissoes) {
            @Composable {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    factory = { ctx ->
                        PreviewView(ctx).also { pv -> phone.vincularCamera(lifecycleOwner, pv) }
                    },
                )
            }
        } else {
            null
        }

    // Cada cartão vira um "bloco pronto" — o layout de tablet abaixo decide
    // onde cada um entra (preparação em 3 colunas / bancada em 2 painéis),
    // sem duplicar nenhum parâmetro. Design aprovado em 24/08/2026.
    val cartaoOculos: @Composable () -> Unit = {
        CartaoOculos(
            destaque = passo == Passo.CONECTAR,
            ehMentra = ehMentra,
            conectado = conectado,
            conectando = conectando,
            temPermissoes = temPermissoes,
            onConectar = { conectarOculos() },
        )
    }
    val cartaoWifi: @Composable () -> Unit = {
        if (ehMentra) CartaoWifiOculos(
            destaque = !wifiOculos && conectado,
            conectado = conectado,
            wifiOculos = wifiOculos,
            ssidOculos = ssidOculos,
            ssid = wifiSsid,
            onSsid = { wifiSsid = it },
            senha = wifiSenha,
            onSenha = { wifiSenha = it },
            onEnviar = {
                (device as? MentraGlassesDevice)
                    ?.configurarWifi(wifiSsid.trim(), wifiSenha)
            },
        )
    }
    val cartaoServidor: @Composable () -> Unit = {
        CartaoServidor(
            destaque = passo == Passo.SESSAO,
            // Leitura de lacre agora é PELOS ÓCULOS (o scanner da câmera do
            // tablet saiu). No modo PHONE (sem óculos), o leitor local continua
            // como plano B de teste.
            onLerLacre = {
                if (ehMentra) lerLacrePelosOculos()
                else LeitorCodigo.ler(
                    context,
                    onOk = { codigo ->
                        protocolo = codigo
                        status = "Lacre lido: $codigo — abrindo perícia..."
                        iniciarSessao()
                    },
                    onErro = { msg -> status = "Leitura do lacre: $msg" },
                )
            },
            protocolo = protocolo,
            onProtocolo = { protocolo = it },
            editavel = !temSessao && !ocupado,
            temSessao = temSessao,
            ocupado = ocupado,
            fotosEnviadas = fotosEnviadas,
            temLaudo = laudoId != null,
            onIniciar = { iniciarSessao() },
            onFinalizar = { finalizarSessao() },
        )
    }
    val cartaoCaptura: @Composable () -> Unit = {
        CartaoCaptura(
            destaque = passo == Passo.CAPTURAR,
            podeCapturar = podeCapturar,
            motivoBloqueio = motivoBloqueio,
            fotosEnviadas = fotosEnviadas,
            vozAtiva = vozAtiva,
            ouvindoPelosOculos = ouvindoPelosOculos,
            gravandoAudio = gravandoAudio,
            previewCamera = previewCamera,
            onFoto = { device.capturarFoto() },
            onVoz = { alternarComandoDeVoz() },
            onAudio = {
                if (gravandoAudio) device.pararAudio() else device.iniciarAudio()
                gravandoAudio = !gravandoAudio
            },
            onFinalizar = if (temSessao) ({ finalizarSessao() }) else null,
            finalizando = ocupado,
        )
    }
    // O que os óculos estão vendo, ao vivo (só no modo MENTRA — no PHONE a
    // pré-visualização da câmera já mora dentro do cartão de captura).
    val cartaoVisao: @Composable () -> Unit = {
        if (ehMentra) CartaoVisaoOculos(urlFlv = urlVisao, aoVivo = videoLigado)
    }
    // O laudo sendo escrito pelo backend (o card some enquanto não há laudo).
    val cartaoLaudo: @Composable () -> Unit = {
        laudoId?.let { CartaoLaudo(trechos = trechosLaudo, montando = trechosLaudo.isEmpty()) }
    }
    val cartaoEvidencia: @Composable () -> Unit = {
        ultima?.let { CartaoEvidencia(it) }
    }
    val cartaoFichaLacre: @Composable () -> Unit = {
        fichaLacre?.let { f ->
            CartaoFichaLacre(
                ficha = f,
                abrindo = ocupado,
                onAbrirPericia = { iniciarSessao() },
                onLerOutro = { fichaLacre = null; lerLacrePelosOculos() },
            )
        }
    }
    val cartaoAssistente: @Composable () -> Unit = {
        if (ehMentra) CartaoAssistenteIa(
            ativo = ponteGemini != null,
            perito = iaPerito,
            resposta = iaResposta,
            onAlternar = { alternarAssistenteIa() },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // background ANTES do inset: a cor do cabeçalho sobe por baixo da barra
        // de status, em vez de deixar uma faixa de outra cor lá em cima.
        Column(
            Modifier
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding()
        ) {
            BarraDeTopo(
                titulo = "PeritaVision",
                subtitulo = if (temSessao) "Protocolo ${protocolo.trim()} · sessão aberta" else SLOGAN_APP,
                logo = R.drawable.logo_politec,
            )
        }
        FaixaProntidao(prontidao)

        // ── LAYOUT DE TABLET (design aprovado 24/08/2026) ────────────────
        // Sem sessão: PREPARAÇÃO — os 3 passos lado a lado (óculos → Wi-Fi →
        //   abrir a perícia), cada coluna com rolagem própria. O laudo recém-
        //   gerado aparece na 3ª coluna, embaixo do cartão do servidor.
        // Com sessão: BANCADA — visão dos óculos + captura no painel esquerdo
        //   (o maior), evidência e apoio no direito.
        if (!temSessao) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .imePadding()
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Spacer(Modifier.height(2.dp))
                    cartaoOculos()
                }
                if (ehMentra) {
                    Column(
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Spacer(Modifier.height(2.dp))
                        cartaoWifi()
                    }
                }
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Spacer(Modifier.height(2.dp))
                    cartaoFichaLacre()
                    cartaoServidor()
                    cartaoAssistente()
                    cartaoLaudo()
                    cartaoEvidencia()
                    RodapeMarca(NOME_EMPRESA)
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .imePadding()
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1.3f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Spacer(Modifier.height(2.dp))
                    cartaoVisao()
                    cartaoCaptura()
                }
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Spacer(Modifier.height(2.dp))
                    cartaoAssistente()
                    cartaoLaudo()
                    cartaoEvidencia()
                    cartaoWifi()
                    cartaoOculos()
                    RodapeMarca(NOME_EMPRESA)
                }
            }
        }

        Column(
            Modifier
                .background(MaterialTheme.colorScheme.surface)
                .navigationBarsPadding()
        ) {
            BarraDeStatus(status, tomStatus)
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  CARTÕES — cada um é só apresentação: recebe estado, devolve toques.
// ══════════════════════════════════════════════════════════════════════════

@Composable
private fun CartaoOculos(
    destaque: Boolean,
    ehMentra: Boolean,
    conectado: Boolean,
    conectando: Boolean,
    temPermissoes: Boolean,
    onConectar: () -> Unit,
) {
    if (!ehMentra) {
        // Modo de teste: a "muleta" é a câmera do celular. Deixar isso explícito
        // na tela evita a pior confusão possível numa demonstração — achar que
        // está usando os óculos quando não está.
        CartaoRecolhivel(
            titulo = "Dispositivo",
            resumo = "Câmera do celular (modo de teste)",
            etiqueta = if (temPermissoes) "pronto" else "sem permissão",
            tomEtiqueta = if (temPermissoes) Tom.OK else Tom.ERRO,
            abertoInicial = !temPermissoes,
        ) {
            TextoApoio(
                "TIPO_DISPOSITIVO está em PHONE. A foto é tirada pelo celular e " +
                    "selada localmente — os óculos não participam."
            )
        }
        return
    }

    if (conectado && !destaque) {
        CartaoRecolhivel(
            titulo = "Óculos Mentra Live",
            resumo = "Conectado por Bluetooth",
            etiqueta = "conectado",
            tomEtiqueta = Tom.OK,
        ) {
            TextoApoio(
                "O Bluetooth leva os comandos e o áudio do microfone. A foto sobe " +
                    "pela Wi-Fi dos óculos."
            )
        }
        return
    }

    CartaoPv(destaque = destaque) {
        CabecalhoCartao(
            titulo = "Óculos Mentra Live",
            etiqueta = when {
                conectado -> "conectado"
                conectando -> "procurando"
                else -> "desconectado"
            },
            tomEtiqueta = when {
                conectado -> Tom.OK
                conectando -> Tom.ATENCAO
                else -> Tom.ERRO
            },
        )
        TextoApoio(
            when {
                conectado -> "Conectado e pronto para capturar."
                conectando -> "Procurando os óculos por Bluetooth..."
                else -> "Ligue os óculos, deixe-os por perto e toque em conectar."
            }
        )
        Spacer(Modifier.height(12.dp))
        BotaoPrimario(
            texto = when {
                conectado -> "Óculos conectado"
                conectando -> "Procurando óculos..."
                else -> "Conectar óculos"
            },
            icone = if (conectado) R.drawable.ic_pv_check else R.drawable.ic_pv_bluetooth,
            habilitado = !conectado && !conectando,
            onClick = onConectar,
        )
    }
}

@Composable
private fun CartaoWifiOculos(
    destaque: Boolean,
    conectado: Boolean,
    wifiOculos: Boolean,
    ssidOculos: String?,
    ssid: String,
    onSsid: (String) -> Unit,
    senha: String,
    onSenha: (String) -> Unit,
    onEnviar: () -> Unit,
) {
    CartaoRecolhivel(
        titulo = "Wi-Fi dos óculos",
        resumo = if (wifiOculos) "Rede ${ssidOculos ?: "—"}" else "Sem Wi-Fi — a foto não sobe",
        etiqueta = if (wifiOculos) "conectado" else "pendente",
        tomEtiqueta = if (wifiOculos) Tom.OK else Tom.ERRO,
        abertoInicial = !wifiOculos,
        destaque = destaque,
    ) {
        TextoApoio(
            if (wifiOculos) {
                "É por esta rede que o JPEG sobe ao servidor."
            } else {
                "Fotos e vídeo sobem pelo Wi-Fi DOS ÓCULOS (rede 2.4 GHz com internet). " +
                    "Envie a rede do local antes de capturar."
            }
        )
        Spacer(Modifier.height(10.dp))
        CampoPv(
            valor = ssid,
            onValueChange = onSsid,
            rotulo = "Nome da rede (SSID)",
            habilitado = conectado,
        )
        Spacer(Modifier.height(9.dp))
        CampoPv(
            valor = senha,
            onValueChange = onSenha,
            rotulo = "Senha da rede",
            habilitado = conectado,
        )
        Spacer(Modifier.height(12.dp))
        BotaoTonal(
            texto = "Enviar Wi-Fi aos óculos",
            icone = R.drawable.ic_pv_wifi,
            habilitado = conectado,
            onClick = onEnviar,
        )
    }
}

@Composable
private fun CartaoServidor(
    destaque: Boolean,
    onLerLacre: () -> Unit,
    protocolo: String,
    onProtocolo: (String) -> Unit,
    editavel: Boolean,
    temSessao: Boolean,
    ocupado: Boolean,
    fotosEnviadas: Int,
    temLaudo: Boolean,
    onIniciar: () -> Unit,
    onFinalizar: () -> Unit,
) {
    // Sessão aberta fora do passo de destaque: o Finalizar mora no cartão de
    // captura — este cartão simplesmente sai da frente.
    if (temSessao && !destaque) return

    CartaoPv(destaque = destaque) {
        CabecalhoCartao(
            titulo = "Perícia",
            etiqueta = if (temSessao) "sessão aberta" else "entrar",
            tomEtiqueta = if (temSessao) Tom.OK else Tom.NEUTRO,
        )
        TextoApoio(
            if (temSessao) {
                "Sessão aberta. Finalizar manda o backend montar o laudo."
            } else {
                "Informe o protocolo — os dados da perícia vêm do ATENA."
            }
        )
        Spacer(Modifier.height(10.dp))
        if (!temSessao) {
            CampoPv(
                valor = protocolo,
                onValueChange = onProtocolo,
                rotulo = "Nº do protocolo (ATENA)",
                habilitado = editavel,
            )
            Spacer(Modifier.height(12.dp))
            BotaoPrimario(
                texto = if (ocupado) "Sincronizando com o ATENA..." else "Iniciar perícia",
                icone = R.drawable.ic_pv_play,
                habilitado = !ocupado,
                onClick = onIniciar,
            )
            Spacer(Modifier.height(8.dp))
            BotaoContorno(
                texto = "Ler lacre (código de barras)",
                icone = R.drawable.ic_pv_camera,
                habilitado = !ocupado,
                onClick = onLerLacre,
            )
        } else {
            BotaoContorno(
                texto = if (ocupado) "Finalizando..." else "Finalizar sessão (gera laudo)",
                icone = R.drawable.ic_pv_stop,
                habilitado = !ocupado,
                onClick = onFinalizar,
            )
        }
        if (temSessao) {
            Spacer(Modifier.height(4.dp))
            Contador(fotosEnviadas, "fotos enviadas ao backend")
        }
        if (temLaudo) {
            Spacer(Modifier.height(10.dp))
            TextoApoio("Laudo pronto — revise e baixe no site do PeritaVision.", Tom.OK)
        }
    }
}

@Composable
private fun CartaoCaptura(
    destaque: Boolean,
    podeCapturar: Boolean,
    motivoBloqueio: String?,
    fotosEnviadas: Int,
    vozAtiva: Boolean,
    ouvindoPelosOculos: Boolean,
    gravandoAudio: Boolean,
    previewCamera: (@Composable () -> Unit)?,
    onFoto: () -> Unit,
    onVoz: () -> Unit,
    onAudio: () -> Unit,
    onFinalizar: (() -> Unit)? = null,
    finalizando: Boolean = false,
) {
    CartaoPv(destaque = destaque) {
        CabecalhoCartao(
            titulo = "Captura de evidência",
            etiqueta = if (podeCapturar) "liberada" else "bloqueada",
            tomEtiqueta = if (podeCapturar) Tom.OK else Tom.NEUTRO,
        )
        if (motivoBloqueio != null) {
            TextoApoio(motivoBloqueio)
        } else if (fotosEnviadas > 0) {
            Contador(fotosEnviadas, "fotos enviadas e seladas")
        } else {
            TextoApoio("Toque no botão, use a haste dos óculos, ou fale \"capturar\".")
        }

        if (previewCamera != null) {
            Spacer(Modifier.height(12.dp))
            previewCamera()
        }

        Spacer(Modifier.height(12.dp))
        BotaoPrimario(
            texto = "Tirar foto",
            icone = R.drawable.ic_pv_camera,
            habilitado = podeCapturar,
            grande = true,
            onClick = onFoto,
        )

        if (vozAtiva && podeCapturar) {
            AvisoEscuta(
                titulo = if (ouvindoPelosOculos) "Óculos ouvindo" else "Celular ouvindo",
                detalhe = "diga \"capturar\" ou \"finalizar\"",
            )
        }

        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            BotaoContorno(
                texto = if (vozAtiva) "Parar voz" else "Comando de voz",
                icone = R.drawable.ic_pv_mic,
                habilitado = podeCapturar,
                tom = if (vozAtiva) Tom.ERRO else Tom.NEUTRO,
                modifier = Modifier.weight(1f),
                onClick = onVoz,
            )
            BotaoContorno(
                texto = if (gravandoAudio) "Parar" else "Narração",
                icone = if (gravandoAudio) R.drawable.ic_pv_stop else R.drawable.ic_pv_mic,
                habilitado = podeCapturar,
                tom = if (gravandoAudio) Tom.ERRO else Tom.NEUTRO,
                modifier = Modifier.weight(1f),
                onClick = onAudio,
            )
        }
        if (onFinalizar != null) {
            Spacer(Modifier.height(12.dp))
            BotaoContorno(
                texto = if (finalizando) "Finalizando..." else "Finalizar sessão (gera laudo)",
                icone = R.drawable.ic_pv_stop,
                habilitado = !finalizando,
                onClick = onFinalizar,
            )
        }
    }
}

@Composable
private fun CartaoVisaoOculos(urlFlv: String?, aoVivo: Boolean) {
    CartaoPv {
        CabecalhoCartao(
            titulo = "Visão dos óculos",
            etiqueta = if (aoVivo && urlFlv != null) "ao vivo" else "sem vídeo",
            tomEtiqueta = if (aoVivo && urlFlv != null) Tom.OK else Tom.NEUTRO,
        )
        if (urlFlv == null || !aoVivo) {
            TextoApoio("O vídeo dos óculos aparece aqui, ao vivo, assim que a sessão abrir e a transmissão começar.")
            return@CartaoPv
        }
        val context = LocalContext.current
        // Player recriado quando a URL muda (nova sessão) e liberado quando o
        // cartão sai de cena — sem isso o decodificador fica preso em segundo plano.
        val player = remember(urlFlv) {
            androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
                setMediaItem(androidx.media3.common.MediaItem.fromUri(urlFlv))
                prepare()
                playWhenReady = true
            }
        }
        DisposableEffect(player) { onDispose { player.release() } }
        Spacer(Modifier.height(10.dp))
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(MaterialTheme.shapes.small),
            factory = { ctx ->
                androidx.media3.ui.PlayerView(ctx).apply {
                    useController = false
                    this.player = player
                }
            },
        )
        Spacer(Modifier.height(6.dp))
        TextoApoio("Atraso de alguns segundos é normal (o vídeo passa pelo servidor).")
    }
}

@Composable
private fun CartaoLaudo(trechos: List<BackendClient.TrechoLaudo>, montando: Boolean) {
    CartaoPv {
        CabecalhoCartao(
            titulo = "Laudo",
            etiqueta = if (montando) "escrevendo..." else "rascunho pronto",
            tomEtiqueta = if (montando) Tom.ATENCAO else Tom.OK,
        )
        if (montando) {
            TextoApoio("O servidor está montando o laudo com o que foi capturado e narrado — as seções aparecem aqui conforme ficam prontas.")
            return@CartaoPv
        }
        Spacer(Modifier.height(6.dp))
        trechos.forEach { t ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.background)
                    .padding(13.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "SEÇÃO ${t.secao} · ${t.titulo.uppercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = PvTheme.extras.textoSuave,
                        modifier = Modifier.weight(1f),
                    )
                    // Cor = origem, igual ao painel web: perito confirma (verde),
                    // rascunho da IA pede revisão (âmbar), Atena é dado de entrada.
                    Etiqueta(
                        texto = when (t.origem) { "ia" -> "Rascunho IA"; "perito" -> "Perito"; else -> "Atena" },
                        tom = when (t.origem) { "ia" -> Tom.ATENCAO; "perito" -> Tom.OK; else -> Tom.NEUTRO },
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    t.texto,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextoApoio("Revisão e assinatura continuam no painel — aqui é acompanhamento.")
    }
}

@Composable
private fun CartaoFichaLacre(
    ficha: BackendClient.FichaLacre,
    abrindo: Boolean,
    onAbrirPericia: () -> Unit,
    onLerOutro: () -> Unit,
) {
    CartaoPv {
        CabecalhoCartao(
            titulo = "Lacre lido pelos óculos",
            etiqueta = ficha.codigo,
            tomEtiqueta = Tom.OK,
        )
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.background)
                .padding(13.dp),
        ) {
            Text(
                "Protocolo ${ficha.numeroProtocolo}",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (ficha.naturezas.isNotEmpty()) {
                Text(
                    ficha.naturezas.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            LinhaDado("Solicitante", ficha.solicitante ?: "a confirmar")
            LinhaDado("Unidade", ficha.unidadeRequisitante ?: "a confirmar")
            LinhaDado("Vítima", ficha.vitima ?: "não informada")
            LinhaDado("Data do fato", ficha.dataOcorrencia ?: "não informada")
            LinhaDado("Objetos", "${ficha.quantidadeMateriais}", ultima = ficha.materiais.isEmpty())
            if (ficha.materiais.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "MATERIAIS",
                    style = MaterialTheme.typography.labelSmall,
                    color = PvTheme.extras.textoSuave,
                )
                Spacer(Modifier.height(4.dp))
                ficha.materiais.forEach { m ->
                    Text(
                        "• $m",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        BotaoPrimario(
            texto = if (abrindo) "Abrindo..." else "Abrir perícia deste lacre",
            habilitado = !abrindo,
            onClick = onAbrirPericia,
        )
        Spacer(Modifier.height(8.dp))
        BotaoContorno(texto = "Ler outro lacre", onClick = onLerOutro)
    }
}

@Composable
private fun CartaoAssistenteIa(
    ativo: Boolean,
    perito: String,
    resposta: String,
    onAlternar: () -> Unit,
) {
    CartaoPv {
        CabecalhoCartao(
            titulo = "Assistente IA (teste)",
            etiqueta = if (ativo) "ativo" else "desligado",
            tomEtiqueta = if (ativo) Tom.OK else Tom.NEUTRO,
        )
        if (!ativo) {
            TextoApoio(
                "Assistente de voz pelos óculos (Gemini). Liga sozinho quando a sessão " +
                    "abre; aqui você religa se tiver desligado. Uma cópia do áudio vai aos " +
                    "servidores do Google — use apenas em teste/bancada, não em caso real.",
            )
            Spacer(Modifier.height(10.dp))
            BotaoContorno(texto = "Ligar assistente", onClick = onAlternar)
            return@CartaoPv
        }
        Spacer(Modifier.height(8.dp))
        if (perito.isNotBlank()) {
            Text(
                "Você: $perito",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
        }
        if (resposta.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.background)
                    .padding(12.dp),
            ) {
                Text(
                    resposta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        if (perito.isBlank() && resposta.isBlank()) {
            TextoApoio("Ouvindo pelos óculos — pergunte em voz alta (ex.: \"qual o próximo passo do método?\").")
            Spacer(Modifier.height(8.dp))
        }
        BotaoContorno(texto = "Desligar assistente", onClick = onAlternar)
    }
}

@Composable
private fun CartaoEvidencia(ev: Evidencia) {
    CartaoPv {
        CabecalhoCartao(
            titulo = "Última evidência",
            etiqueta = "selada",
            tomEtiqueta = Tom.OK,
        )
        Spacer(Modifier.height(6.dp))
        LinhaDado("Tipo", ev.tipo.name)
        LinhaDado("Arquivo", ev.caminhoArquivo.substringAfterLast('/'))
        LinhaDado("SHA-256", ev.sha256.take(20) + "…", mono = true)
        LinhaDado("UTC", ev.timestampUtc, mono = true)
        LinhaDado("Caso", ev.casoId ?: "sem caso vinculado", ultima = true)
    }
}
