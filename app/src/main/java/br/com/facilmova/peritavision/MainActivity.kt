package br.com.facilmova.peritavision

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import br.com.facilmova.peritavision.scan.LeitorCodigo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import br.com.facilmova.peritavision.assistida.CanalAssistido
import br.com.facilmova.peritavision.audio.FeedbackDeVoz
import br.com.facilmova.peritavision.device.GlassesDevice
import br.com.facilmova.peritavision.device.GlassesDeviceFactory
import br.com.facilmova.peritavision.device.GlassesEvent
import br.com.facilmova.peritavision.device.MentraGlassesDevice
import br.com.facilmova.peritavision.device.PhoneGlassesDevice
import br.com.facilmova.peritavision.domain.CofreCustodia
import br.com.facilmova.peritavision.domain.Evidencia
import br.com.facilmova.peritavision.domain.SelarCustodia
import br.com.facilmova.peritavision.domain.TipoEvidencia
import br.com.facilmova.peritavision.net.AudioStreamer
import br.com.facilmova.peritavision.net.PonteGemini
import br.com.facilmova.peritavision.data.ConfiguracoesApp
import br.com.facilmova.peritavision.ui.TelaConfiguracoes
import br.com.facilmova.peritavision.net.BackendClient
import br.com.facilmova.peritavision.ui.AvisoEscuta
import br.com.facilmova.peritavision.ui.BarraDeStatus
import br.com.facilmova.peritavision.ui.BarraDeTopo
import br.com.facilmova.peritavision.ui.BotaoContorno
import br.com.facilmova.peritavision.ui.BotaoPrimario
import br.com.facilmova.peritavision.ui.BotaoTonal
import br.com.facilmova.peritavision.ui.CabecalhoCartao
import br.com.facilmova.peritavision.ui.CampoPv
import br.com.facilmova.peritavision.ui.BalaoConversa
import br.com.facilmova.peritavision.ui.BarraProgresso
import br.com.facilmova.peritavision.ui.LinhaCampo
import br.com.facilmova.peritavision.ui.SecaoLaudoPv
import br.com.facilmova.peritavision.ui.CartaoPasso
import br.com.facilmova.peritavision.ui.CartaoPv
import br.com.facilmova.peritavision.ui.MolduraVisor
import br.com.facilmova.peritavision.ui.TituloSecao
import br.com.facilmova.peritavision.ui.Contador
import br.com.facilmova.peritavision.ui.Etiqueta
import br.com.facilmova.peritavision.ui.FaixaProntidao
import br.com.facilmova.peritavision.ui.LinhaDado
import br.com.facilmova.peritavision.ui.PeritavisionTheme
import br.com.facilmova.peritavision.ui.Prontidao
import br.com.facilmova.peritavision.ui.PvTheme
import br.com.facilmova.peritavision.ui.RodapeAssinatura
import br.com.facilmova.peritavision.ui.TextoApoio
import br.com.facilmova.peritavision.ui.Tom
import br.com.facilmova.peritavision.voice.VoiceTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Quanto da requisição vai para a sessão da BANCADA (a IA que fala com o perito). */
private const val LIMITE_REQUISICAO_BANCADA = 8000

private const val SLOGAN_APP = "Perícia assistida · POLITEC-MT"

// Hardware alvo: Mentra Live (BLE pelo proprio app).
// Troque para PHONE para testar sem oculos (usa a camera do celular).
private val TIPO_DISPOSITIVO = GlassesDeviceFactory.Tipo.MENTRA

private val BACKEND_PADRAO = BuildConfig.PV_BACKEND
// Conta de DISPOSITIVO: o app autentica sozinho; a identidade do perito fica no site.
private val MATRICULA_PADRAO = BuildConfig.PV_MATRICULA
private val SENHA_PADRAO = BuildConfig.PV_SENHA
// PV_PROTOCOLO (local.properties) NÃO preenche mais a tela: o campo começa
// vazio — o protocolo de teste aparecendo em toda abertura induzia a erro.

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Desenha de ponta a ponta (obrigatório a partir do targetSdk 35).
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

/** Onde a tela está, no fluxo do perito. */
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

    var ponteGemini by remember { mutableStateOf<PonteGemini?>(null) }
    // Perícia assistida remota: enquanto a sala existe, a perita oficial ocupa o lugar do assistente de IA.
    var canalAssistido by remember { mutableStateOf<CanalAssistido?>(null) }
    var codigoSalaAssistida by remember { mutableStateOf<String?>(null) }
    var peritaOnline by remember { mutableStateOf(false) }
    var instrucaoAssistida by remember { mutableStateOf<InstrucaoAssistida?>(null) }
    var marcacaoDaPerita by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var assistidaStatus by remember { mutableStateOf<String?>(null) }
    var assistidaMudo by remember { mutableStateOf(false) }
    // Aba Configurações (engrenagem): roteiro fixo ou "perguntar", e modelo.
    val config = remember { ConfiguracoesApp(context) }
    var mostrarConfiguracoes by remember { mutableStateOf(false) }
    /** Roteiro em uso na sessão de trabalho ("Trilha A — Faca…"); null = ainda
     *  não definido (a IA está perguntando, ou o assistente está desligado). */
    var iaTrilha by remember { mutableStateOf<String?>(null) }
    var iaPerguntandoTrilha by remember { mutableStateOf(false) }
    /** MODO da IA: conversa | silencio | pausa (troca por palavra; toque é reserva). */
    var iaModo by remember { mutableStateOf("conversa") }
    /** QUEM MANDA NA GRAVAÇÃO. */
    var gravacaoPausada by remember { mutableStateOf(false) }
    /** Achados registrados pela IA nesta sessão (registrar_achado), para o cartão do laudo. */
    var achados by remember { mutableStateOf<List<String>>(emptyList()) }
    // Bipes curtos no lugar de fala para confirmar modo e achado (em silêncio
    // a IA não pode falar "ok"). STREAM_MUSIC segue a rota Bluetooth dos óculos.
    val bipes = remember { runCatching { android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 80) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { runCatching { bipes?.release() } } }
    fun bipe(tipo: String) {
        val b = bipes ?: return
        runCatching {
            when (tipo) {
                "conversa" -> b.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 180)
                "silencio" -> b.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120)
                "pausa" -> b.startTone(android.media.ToneGenerator.TONE_PROP_NACK, 220)
                else -> b.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 90) // achado
            }
        }
    }
    /** Diagnóstico da saída de voz (por onde o som sai; erros). */
    var iaVoz by remember { mutableStateOf("") }
    /** TELA APAGADA por comando de voz ("PeritaVision, apaga a tela") — para luz forense com a sala escura. */
    var telaApagada by remember { mutableStateOf(false) }
    var iaPerito by remember { mutableStateOf("") }
    /** Quando a última captura foi disparada (qualquer via). */
    var ultimaCapturaMs by remember { mutableStateOf(0L) }
    var pedidoFinalizarMs by remember { mutableStateOf(0L) }
    /** Encerramento em curso — trava propria, para o pedido nao ser engolido
     *  pelo `ocupado` de outra operacao (ver finalizarSessao). */
    var finalizando by remember { mutableStateOf(false) }
    /** Fala do perito acumulada para a NARRAÇÃO do laudo (tudo vai; o cartão
     *  na tela mostra só as perguntas com o chamado "PeritaVision"). */
    var narracaoPendente by remember { mutableStateOf("") }
    // NARRAÇÕES DA BANCADA: cópia local do que já foi gravado no backend (pericia.trecho_narracao).
    var narracoes by remember { mutableStateOf<List<String>>(emptyList()) }
    var narracaoUltimoMs by remember { mutableStateOf(0L) }
    /** true enquanto a fala em curso contém o chamado — controla a exibição. */
    var falaComChamado by remember { mutableStateOf(false) }
    var iaResposta by remember { mutableStateOf("") }
    /** true quando o servidor confirma que o vídeo dos óculos chegou ao Gemini. */
    var iaEnxergando by remember { mutableStateOf(false) }
    /** true só durante a janela em que a IA está realmente OLHANDO. */
    var iaOlhandoAgora by remember { mutableStateOf(false) }

    /** true quando o perito DESLIGOU o assistente à mão nesta sessão. */
    var iaDesligadaManual by remember { mutableStateOf(false) }

    /** UMA voz só na bancada. */
    fun falarSeSemIa(texto: String) {
        val iaExiste = BuildConfig.PV_PONTE_URL.isNotBlank() && !iaDesligadaManual
        if (!iaExiste) vozFeedback.falar(texto)
    }

    val voz = remember {
        VoiceTrigger(context, onComando = {
            ultimaCapturaMs = System.currentTimeMillis()
            falarSeSemIa("Capturando"); device.capturarFoto()
        })
    }
    var vozAtiva by remember { mutableStateOf(false) }
    /** true depois que os oculos falharem em transcrever: escuta pelo celular. */
    var usarVozDoCelular by remember { mutableStateOf(false) }
    /** Canal que leva o audio DOS OCULOS ao servico de voz do backend. */
    var audioStreamer by remember { mutableStateOf<AudioStreamer?>(null) }
    LaunchedEffect(voz) { voz.onStatus = { msg -> status = msg } }

    val escopo = rememberCoroutineScope()
    var enderecoBackend by remember { mutableStateOf(BACKEND_PADRAO) }
    val backend = remember { BackendClient(BACKEND_PADRAO) }
    var sessaoId by remember { mutableStateOf<String?>(null) }
    var laudoId by remember { mutableStateOf<String?>(null) }
    var ocupado by remember { mutableStateOf(false) }
    var fotosEnviadas by remember { mutableIntStateOf(0) }
    var quadrosMarcados by remember { mutableIntStateOf(0) }
    /** Nome (ou matrícula) de quem o SERVIDOR pôs como responsável pela
     *  perícia — o que decide em qual lista o laudo aparece no painel. */
    var peritoDaSessao by remember { mutableStateOf<String?>(null) }
    var fotosDaPericia by remember { mutableStateOf<List<FotoNaTela>>(emptyList()) }
    var fotoAmpliada by remember { mutableStateOf<FotoNaTela?>(null) }
    /** A mesma foto em resolução alta, baixada quando o perito amplia. */
    var bitmapAmpliado by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(fotoAmpliada?.id) {
        bitmapAmpliado = null
        val f = fotoAmpliada ?: return@LaunchedEffect
        bitmapAmpliado = withContext(Dispatchers.IO) {
            runCatching { miniaturaDe(backend.baixarFoto(f.id), alvoPx = 1600) }.getOrNull()
        }
    }

    fun ligarAssistenteIa() {
        if (ponteGemini != null) return
        if (canalAssistido != null) {
            status = "Assistente IA desligado: a perita oficial está conduzindo esta perícia."
            return
        }
        if (BuildConfig.PV_PONTE_URL.isBlank()) {
            status = "Assistente IA: configure pv.ponte no local.properties."
            return
        }
        val ponte = PonteGemini(
            BuildConfig.PV_PONTE_URL,
            sessaoId ?: "bancada-teste",
            modelo = config.modelo,
            trilha = config.trilhaParaPonte(),
            palavras = config.palavrasParaPonte(),
        )
        ponte.onStatus = { msg -> status = msg }
        ponte.onModo = { modo, origem ->
            if (origem != "abertura") {
                iaModo = modo
                bipe(modo)
                gravacaoPausada = modo == "pausa"
            } else if (gravacaoPausada) {
                // Sessão Gemini reabriu (reconexão, troca de trilha, redeploy) e o servidor voltou para conversa.
                ponteGemini?.definirModo("pausa")
            } else {
                iaModo = modo
            }
            status = when (iaModo) {
                "silencio" -> "IA em silêncio — ouvindo e registrando; diga a palavra para conversar."
                "pausa" -> "Gravação em pausa — vídeo parado, nada vai para o laudo. Diga \"assistente\" ou \"silêncio\" para voltar."
                else -> "IA em conversa."
            }
        }
        ponte.onAchado = { a ->
            val item = a.optString("item").takeIf { it.isNotBlank() }?.let { "item $it: " } ?: ""
            val campo = a.optString("campo").takeIf { it.isNotBlank() }?.let { " de $it" } ?: ""
            achados = achados + "${item}${a.optString("tipo")}$campo — ${a.optString("valor")}"
            bipe("achado")
            sessaoId?.let { sid ->
                escopo.launch { backend.registrarEvento(sid, "marcador", "achado", "ia", a) }
            }
        }
        ponte.onTriagem = { iaPerguntandoTrilha = true; iaTrilha = null }
        ponte.onVoz = { d -> iaVoz = d }
        // Trilha definida: a sessão de trabalho está de pé com o roteiro certo.
        ponte.onTrilha = { id, nome, origem ->
            iaPerguntandoTrilha = false
            iaTrilha = if (id == "nenhuma") nome else "Trilha ${id.uppercase()} — $nome"
            status = when (origem) {
                "caso" -> "Roteiro escolhido pelos materiais do caso: $nome."
                "padrao" -> "Sem roteiro para este material — assistente geral. Fixe uma trilha em Configurações se quiser o passo a passo."
                "memoria" -> "Sessão retomada — roteiro mantido: $nome."
                "perito" -> "Roteiro definido pelo perito: $nome."
                else -> "Roteiro fixado em Configurações: $nome."
            }
            sessaoId?.let { sid ->
                escopo.launch { backend.registrarEvento(sid, "sistema", "trilha:$id", "ponte:$origem") }
            }
        }
        ponte.onVideoAtivo = { iaEnxergando = true }
        ponte.onVisao = { ativa -> iaOlhandoAgora = ativa }
        ponte.onTranscricao = { t ->
            // TUDO que o perito fala vira narração do laudo (buffer com descarga por pausa — ver LaunchedEffect da narração).
            narracaoPendente += t
            narracaoUltimoMs = System.currentTimeMillis()
            // Na TELA, só as falas dirigidas à IA (contêm o chamado).
            if (iaResposta.isNotBlank()) { iaPerito = ""; iaResposta = ""; falaComChamado = false }
            val comChamado = falaComChamado ||
                t.contains("peritavision", ignoreCase = true) ||
                t.contains("perita vision", ignoreCase = true)
            if (comChamado) {
                falaComChamado = true
                iaPerito += t
            }
        }
        ponte.onResposta = { t -> iaResposta += t }
        iaEnxergando = false
        ponte.conectar()
        ponteGemini = ponte
        status = "Assistente IA conectando..."
    }
    LaunchedEffect(ponteGemini, sessaoId) {
        val id = sessaoId ?: return@LaunchedEffect
        if (ponteGemini == null) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(700)
            val pronta = narracaoPendente
            val parada = System.currentTimeMillis() - narracaoUltimoMs > 2_000
            if (pronta.isNotBlank() && (parada || pronta.length > 1_500)) {
                narracaoPendente = ""
                narracoes = narracoes + pronta.trim()  
                backend.narrar(id, pronta)
            }
        }
    }

    fun alternarAssistenteIa() {
        val ativa = ponteGemini
        if (ativa != null) {
            ativa.encerrar()
            ponteGemini = null
            iaEnxergando = false
            iaTrilha = null
            iaPerguntandoTrilha = false
            iaModo = "conversa"
            iaDesligadaManual = true // o TTS reassume as falas da bancada
            status = "Assistente IA desligado."
            return
        }
        iaDesligadaManual = false
        ligarAssistenteIa()
    }
    // AUTOMÁTICO: sessão abriu → assistente liga sozinho; sessão fechou → desliga junto.
    LaunchedEffect(sessaoId) {
        if (sessaoId != null) {
            iaDesligadaManual = false
            ligarAssistenteIa()
        } else {
            ponteGemini?.encerrar()
            ponteGemini = null
            canalAssistido?.encerrar(avisarServidor = true)
            canalAssistido = null
            codigoSalaAssistida = null
            peritaOnline = false
            instrucaoAssistida = null
            marcacaoDaPerita = null
            assistidaStatus = null
            assistidaMudo = false
            iaPerito = ""
            iaResposta = ""
            iaEnxergando = false
            iaTrilha = null
            iaPerguntandoTrilha = false
            iaModo = "conversa"
            gravacaoPausada = false
            peritoDaSessao = null
            achados = emptyList()
            iaVoz = ""
            telaApagada = false
        }
    }
    LaunchedEffect(telaApagada) {
        val janela = (context as? android.app.Activity)?.window ?: return@LaunchedEffect
        val attrs = janela.attributes
        attrs.screenBrightness = if (telaApagada) 0.01f
            else android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        janela.attributes = attrs
        if (telaApagada) janela.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else janela.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // Wi-Fi DOS OCULOS: o JPEG sobe pela rede do oculos, nao pelo Bluetooth.
    var wifiOculos by remember { mutableStateOf(false) }
    var ssidOculos by remember { mutableStateOf<String?>(null) }
    /** A rede salva já foi enviada NESTA conexão BLE? */
    var wifiEnviadaNestaConexao by remember { mutableStateOf(false) }
    // Rede do local vem salva (Configurações) e vai sozinha aos óculos ao conectar.
    var wifiSsid by remember { mutableStateOf(config.wifiSsid) }
    var wifiSenha by remember { mutableStateOf(config.wifiSenha) }
    var redeDoTablet by remember { mutableStateOf<br.com.facilmova.peritavision.data.RedeDoTablet?>(null) }
    LaunchedEffect(conectado, mostrarConfiguracoes, wifiOculos) {
        val rede = withContext(Dispatchers.IO) {
            runCatching { br.com.facilmova.peritavision.data.redeAtualDoTablet(context) }.getOrNull()
        }
        redeDoTablet = rede
        // Campo vazio (tablet novo, app recém-instalado) e o Android deixou
        // ler o nome: entra pronto. Nunca sobrescreve o que o perito digitou.
        val doTablet = rede?.ssid.orEmpty()
        if (wifiSsid.isBlank() && doTablet.isNotBlank() && rede?.foraDoAlcanceDosOculos == false) {
            wifiSsid = doTablet
            config.wifiSsid = doTablet
        }
    }
    // Matrícula: a última que abriu perícia neste tablet (Configurações), ou a
    // de dev do local.properties na primeira vez. Protocolo: sempre vazio.
    var matricula by remember { mutableStateOf(config.ultimaMatricula.ifBlank { MATRICULA_PADRAO }) }
    // Credencial DO TABLET (local.properties), não do perito: some da tela.
    val senhaPerito = SENHA_PADRAO
    var protocolo by remember { mutableStateOf("") }
    var rtmpUrl by remember { mutableStateOf<String?>(null) }
    var videoLigado by remember { mutableStateOf(false) }

    val videoNoTablet = remember(sessaoId) { config.videoNoTablet }
    val enviarDepois = remember(sessaoId) { config.videoNoTablet && config.videoEnviarDepois }
    val receptor = remember { br.com.facilmova.peritavision.rtmp.ReceptorDeVideo(context) }
    val decodificador = remember { br.com.facilmova.peritavision.rtmp.DecodificadorDeVideo() }
    var decodificadorEstado by remember {
        mutableStateOf(br.com.facilmova.peritavision.rtmp.DecodificadorDeVideo.Estado())
    }
    val custodia = remember {
        br.com.facilmova.peritavision.custodia.CustodiaDeFotos(java.io.File(context.filesDir, "fotos"))
    }
    /** A SurfaceView do visor ao vivo, enquanto está na tela. É dela que sai
     *  o quadro quando os óculos recusam a foto por estarem transmitindo. */
    var visorAoVivo by remember { mutableStateOf<android.view.SurfaceView?>(null) }
    /** Copia o quadro atual do visor ao vivo (PixelCopy). Quem chama recicla o bitmap. */
    suspend fun bitmapDoVisor(maxLargura: Int? = null): android.graphics.Bitmap? {
        val view = visorAoVivo ?: return null
        if (view.width <= 0 || view.height <= 0 || !view.holder.surface.isValid) return null
        val largura = decodificadorEstado.largura.takeIf { it > 0 } ?: view.width
        val altura = decodificadorEstado.altura.takeIf { it > 0 } ?: view.height
        val escala = if (maxLargura != null && largura > maxLargura) maxLargura.toDouble() / largura else 1.0
        val bitmap = android.graphics.Bitmap.createBitmap(
            (largura * escala).toInt().coerceAtLeast(1), (altura * escala).toInt().coerceAtLeast(1),
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        val copiou = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
            runCatching {
                android.view.PixelCopy.request(
                    view, bitmap,
                    { resultado -> if (cont.isActive) cont.resumeWith(Result.success(resultado == android.view.PixelCopy.SUCCESS)) },
                    android.os.Handler(android.os.Looper.getMainLooper()),
                )
            }.onFailure { if (cont.isActive) cont.resumeWith(Result.success(false)) }
        }
        if (!copiou) { bitmap.recycle(); return null }
        return bitmap
    }
    suspend fun jpegDoVisor(maxLargura: Int?, qualidade: Int): ByteArray? {
        val bitmap = bitmapDoVisor(maxLargura) ?: return null
        return withContext(Dispatchers.IO) {
            java.io.ByteArrayOutputStream().use { saida ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, qualidade, saida)
                bitmap.recycle()
                saida.toByteArray()
            }
        }
    }
    suspend fun quadroDoVisor(): ByteArray? = jpegDoVisor(null, 92)
    /** Como [quadroDoVisor], mas REDUZIDO para a IA: até 1024 px de largura,
     *  JPEG 70. É o que vai à ponte 1x por segundo no modo vídeo no tablet. */
    suspend fun quadroParaIa(): ByteArray? = jpegDoVisor(1024, 70)

    fun encerrarSalaAssistida(avisarServidor: Boolean) {
        canalAssistido?.encerrar(avisarServidor)
        canalAssistido = null
        codigoSalaAssistida = null
        peritaOnline = false
        instrucaoAssistida = null
        marcacaoDaPerita = null
        assistidaMudo = false
    }

    /** Abre a sala no servidor, desliga o assistente de IA e conecta a sinalização (voz + visão ao vivo). */
    fun abrirSalaAssistida() {
        val sid = sessaoId ?: return
        val jwt = backend.token ?: run { status = "Sem sessão no servidor — abra a perícia primeiro."; return }
        if (canalAssistido != null || ocupado) return
        ocupado = true
        escopo.launch {
            try {
                val sala = backend.criarSalaAssistida(sid)
                ponteGemini?.encerrar()
                ponteGemini = null
                iaEnxergando = false
                iaPerguntandoTrilha = false
                iaModo = "conversa"
                gravacaoPausada = false
                val canal = CanalAssistido(context, backend.urlSinalAssistida(sid), jwt) { bitmapDoVisor(960) }
                canal.onStatus = { assistidaStatus = it }
                canal.onPeritaOnline = { online ->
                    peritaOnline = online
                    if (!online) instrucaoAssistida = null
                }
                canal.onInstrucao = { id, texto -> instrucaoAssistida = InstrucaoAssistida(id, texto); bipe("achado") }
                canal.onFoto = {
                    val agora = System.currentTimeMillis()
                    if (agora - ultimaCapturaMs >= 6_000) {
                        ultimaCapturaMs = agora
                        device.capturarFoto()
                    }
                }
                canal.onMarcacao = { x, y -> marcacaoDaPerita = x to y }
                canal.onEncerrada = {
                    encerrarSalaAssistida(avisarServidor = false)
                    status = "A perita encerrou a sala. O assistente de IA pode ser religado no cartão do assistente."
                }
                codigoSalaAssistida = sala.codigo
                assistidaStatus = null
                canalAssistido = canal
                canal.conectar()
                backend.registrarEvento(sid, "sistema", "assistida_ia_desligada", "campo")
                status = "Sala aberta — passe o código à perita oficial."
            } catch (e: Exception) {
                status = "Não foi possível abrir a sala: ${e.message}"
            } finally {
                ocupado = false
            }
        }
    }
    LaunchedEffect(marcacaoDaPerita) {
        if (marcacaoDaPerita != null) {
            kotlinx.coroutines.delay(6_000)
            marcacaoDaPerita = null
        }
    }
    LaunchedEffect(canalAssistido, fotosEnviadas) {
        canalAssistido?.estado(fotosEnviadas, null)
    }
    val receptorFotos = remember { br.com.facilmova.peritavision.net.ReceptorDeFotos(custodia) }
    var resumoFotos by remember {
        mutableStateOf(br.com.facilmova.peritavision.custodia.CustodiaDeFotos.Resumo())
    }
    /** Matrícula digitada que o servidor não reconheceu: a perícia ficou no
     *  nome de outro perito, e isso tem de ser DITO na abertura. */
    var matriculaNaoCadastrada by remember { mutableStateOf<String?>(null) }
    /** Diagnóstico do receptor de fotos, para a bancada saber se os óculos
     *  chegaram na porta — sem depender de logcat. */
    var receptorFotosEstado by remember {
        mutableStateOf(br.com.facilmova.peritavision.net.ReceptorDeFotos.Estado())
    }
    /** Sobe ao backend uma foto que já está no tablet, insistindo. */
    suspend fun repassarFoto(requestId: String, arquivo: java.io.File, daSessao: String?, tentativas: Int): Boolean {
        val cred = custodia.credencial(requestId, daSessao) ?: return false
        if (!custodia.tomarParaEnvio(requestId)) return false
        val credencialDoBackend = BackendClient.CredencialCaptura(cred.requestId, cred.webhookUrl, cred.authToken)
        for (t in 1..tentativas) {
            try {
                withContext(Dispatchers.IO) { backend.enviarFoto(credencialDoBackend, arquivo) }
                withContext(Dispatchers.IO) {
                    if (!custodia.confirmarEnvio(requestId, arquivo)) {
                        android.util.Log.w("PV-Fotos", "foto no servidor, mas não consegui marcar ${arquivo.name}")
                    }
                }
                return true
            } catch (e: kotlinx.coroutines.CancellationException) {
                custodia.devolver(requestId)
                throw e
            } catch (e: Exception) {
                val codigo = (e as? br.com.facilmova.peritavision.net.BackendException)?.codigo ?: 0
                if (t > 1 && codigo in listOf(401, 403, 409, 410)) {
                    android.util.Log.i("PV-Fotos", "repasse de $requestId: token queimado ($codigo) — já estava no servidor")
                    withContext(Dispatchers.IO) { custodia.confirmarEnvio(requestId, arquivo) }
                    return true
                }
                android.util.Log.w("PV-Fotos", "repasse de $requestId, tentativa $t: ${e.message}")
                if (t < tentativas) kotlinx.coroutines.delay(3_000L * t)
            }
        }
        custodia.devolver(requestId)
        return false
    }
    var receptorEstado by remember { mutableStateOf(br.com.facilmova.peritavision.rtmp.ReceptorDeVideo.Estado()) }
    var segmentosSubindo by remember { mutableIntStateOf(0) }
    var segmentosSubidos by remember { mutableIntStateOf(0) }
    var segmentosComFalha by remember { mutableIntStateOf(0) }
    /** Segmentos que ficaram no tablet de propósito (modo enviar depois). */
    var segmentosGuardados by remember { mutableIntStateOf(0) }
    // FILA DE ENVIO: perícias finalizadas com vídeo guardado no tablet.
    val filaEnvio = remember { br.com.facilmova.peritavision.rtmp.FilaDeEnvio(receptor) }
    var enviosPendentes by remember { mutableStateOf<List<br.com.facilmova.peritavision.rtmp.FilaDeEnvio.Pendente>>(emptyList()) }
    var enviando by remember { mutableStateOf(false) }
    var progressoEnvio by remember { mutableStateOf<String?>(null) }
    fun recontarPendentes() { enviosPendentes = filaEnvio.pendentes(excetoSessao = sessaoId) }
    /** Sobe tudo o que está guardado. Login com a credencial do tablet, como
     *  no Iniciar perícia — sem sessão aberta não há token vivo. */
    fun enviarPendentes(origem: String) {
        if (enviando || ocupado) return
        escopo.launch {
            enviando = true
            try {
                backend.baseUrl = enderecoBackend.trim()
                progressoEnvio = "Entrando no servidor..."
                withContext(Dispatchers.IO) { backend.login(MATRICULA_PADRAO, senhaPerito) }
                val r = withContext(Dispatchers.IO) {
                    filaEnvio.enviarTudo(backend, excetoSessao = sessaoId) { texto -> escopo.launch { progressoEnvio = texto } }
                }
                progressoEnvio = null
                val motivo = r.ultimoErro?.let { " ($it)" } ?: ""
                status = when {
                    r.concluidas > 0 && r.falhas == 0 -> "${r.concluidas} perícia(s) com vídeo no servidor (${r.bytes / 1_000_000} MB) — laudo em geração"
                    r.concluidas > 0 -> "${r.concluidas} concluída(s), ${r.falhas} ficaram para depois$motivo"
                    // Vídeo chegou inteiro, mas o servidor não aceitou o pedido de laudo (rota ausente, sem permissão...).
                    r.videosSubidos > 0 -> "Vídeo de ${r.videosSubidos} perícia(s) no servidor, mas o laudo não foi pedido$motivo — fica para a próxima tentativa"
                    r.falhas > 0 -> "Nenhum vídeo subiu$motivo — tente de novo"
                    else -> "Nada para enviar"
                }
            } catch (e: Exception) {
                progressoEnvio = null
                status = "Envio dos vídeos falhou: ${e.message}"
            } finally {
                enviando = false
                recontarPendentes()
            }
        }
    }
    LaunchedEffect(sessaoId) {
        recontarPendentes()
        if (sessaoId == null && enviosPendentes.isNotEmpty()) {
            delay(20_000)
            recontarPendentes()
            val emWifi = withContext(Dispatchers.IO) {
                runCatching { br.com.facilmova.peritavision.data.redeAtualDoTablet(context) }.getOrNull() != null
            }
            if (sessaoId == null && emWifi && enviosPendentes.isNotEmpty() && !enviando) enviarPendentes("automático")
        }
    }
    // enviarDepois nas chaves: a lambda abaixo captura o valor, e ele muda de uma perícia para a outra.
    LaunchedEffect(receptor, enviarDepois) {
        receptor.aoMudar = { e -> receptorEstado = e }
        receptor.aoQuadro = { q -> decodificador.aceitar(q) }
        decodificador.aoMudar = { e -> escopo.launch { decodificadorEstado = e } }
        receptor.aoSegmentoFechado = fechado@{ chave, arquivo ->
            if (enviarDepois) {
                // Fica no tablet: sobe em lote, pelo botão ou sozinho com Wi-Fi.
                segmentosGuardados += 1
                android.util.Log.i("PV-Receptor", "segmento ${arquivo.name} guardado para envio posterior (${arquivo.length() / 1_000_000} MB)")
                return@fechado
            }
            segmentosSubindo += 1
            escopo.launch(kotlinx.coroutines.Dispatchers.IO) {
                val inicioMs = arquivo.name.removeSuffix(".flv").toLongOrNull() ?: arquivo.lastModified()
                var ok = false
                for (tentativa in 1..3) {
                    try {
                        val shaServidor = backend.enviarSegmentoVideo(chave, arquivo, inicioMs)
                        val shaLocal = br.com.facilmova.peritavision.domain.Hashing.sha256(arquivo)
                        if (shaServidor.isNotBlank() && !shaServidor.equals(shaLocal, ignoreCase = true)) {
                            throw IllegalStateException("hash divergente (local ${shaLocal.take(12)}, servidor ${shaServidor.take(12)})")
                        }
                        ok = true; break
                    } catch (e: Exception) {
                        android.util.Log.w("PV-Receptor", "segmento ${arquivo.name} tentativa $tentativa: ${e.message}")
                        kotlinx.coroutines.delay(2_000L * tentativa)
                    }
                }
                if (ok) { arquivo.delete(); segmentosSubidos += 1 } else segmentosComFalha += 1
                segmentosSubindo -= 1
            }
        }
    }
    LaunchedEffect(sessaoId) {
        if (sessaoId != null) {
            val ip = withContext(Dispatchers.IO) { receptor.ipNaWifi() }
            val erro = if (ip == null) "tablet sem IP na Wi-Fi" else withContext(Dispatchers.IO) { receptorFotos.ligar(ip) }
            if (erro != null) {
                status = "As fotos vão direto ao servidor ($erro) — se a bancada não tiver internet, elas não chegam"
            }
            withContext(Dispatchers.IO) { custodia.recontarParadas() }
            // VÍDEO QUE FICOU DE OUTRA PERÍCIA.
            val atual = sessaoId
            if (!config.videoEnviarDepois) escopo.launch(Dispatchers.IO) {
                val antigas = receptor.sessoesComSegmentos().filter { it != atual }
                for (antiga in antigas) {
                    for (arquivo in receptor.segmentosDe(antiga)) {
                        val mb = arquivo.length() / 1_000_000.0
                        val inicioMs = arquivo.name.removeSuffix(".flv").toLongOrNull() ?: arquivo.lastModified()
                        val ok = runCatching {
                            val shaServidor = backend.enviarSegmentoVideo(antiga, arquivo, inicioMs)
                            shaServidor.isBlank() || shaServidor.equals(br.com.facilmova.peritavision.domain.Hashing.sha256(arquivo), ignoreCase = true)
                        }.getOrElse { e ->
                            android.util.Log.w("PV-Receptor", "vídeo atrasado ${antiga.take(8)}/${arquivo.name}: ${e.message}"); false
                        }
                        if (!ok) break // sem rede ou sessão recusada: tenta na próxima perícia
                        arquivo.delete()
                        status = "Vídeo atrasado da perícia ${antiga.take(8)} subiu ao servidor (${"%.0f".format(mb)} MB) — o laudo dela ganha o vídeo sozinho"
                    }
                }
            }
        } else {
            withContext(Dispatchers.IO) { receptorFotos.desligar() }
        }
    }
    // Chegou um JPEG dos óculos: sela, repassa ao backend e só então conta.
    LaunchedEffect(receptorFotos) {
        receptorFotos.aoMudar = { e -> escopo.launch { receptorFotosEstado = e } }
        custodia.aoMudar = { resumo -> escopo.launch { resumoFotos = resumo } }
        receptorFotos.aoEstranhar = { texto ->
            escopo.launch { status = "Envio dos óculos não virou foto: $texto" }
        }
        // Só aceita depositar foto quem tem uma captura em aberto (o requestId é um uuid do servidor).
        receptorFotos.autorizacaoValida = { requestId, token ->
            val cred = custodia.credencial(requestId, null)
            cred != null && (token == null || cred.authToken.isBlank() || token == cred.authToken)
        }
        receptorFotos.aoReceber = { r ->
            escopo.launch {
                val daSessao = sessaoId
                if (custodia.credencial(r.requestId, daSessao) == null) {
                    android.util.Log.w("PV-Fotos", "foto ${r.requestId} sem credencial: repasse impossível")
                    status = "Foto chegou ao tablet sem autorização casada (${r.requestId.take(8)}) — ficou no tablet"
                    return@launch
                }
                // MINIATURA NA HORA, do arquivo local: é o motivo de a foto vir pelo tablet.
                val local = withContext(Dispatchers.IO) {
                    runCatching { miniaturaDe(r.arquivo.readBytes()) }.getOrNull()
                }
                if (local != null) {
                    fotosDaPericia = fotosDaPericia + FotoNaTela(
                        id = "tablet:${r.requestId}", miniatura = local, doVideo = false,
                        kb = r.bytes / 1024, requestId = r.requestId,
                    )
                }
                val ok = repassarFoto(r.requestId, r.arquivo, daSessao, 4)
                if (ok) {
                    fotosEnviadas += 1
                    status = "Foto $fotosEnviadas no servidor ✓ (${r.bytes / 1024} kB)"
                    falarSeSemIa("Foto capturada. Descreva a evidência.")
                } else {
                    status = "Foto ESTÁ NO TABLET (${r.bytes / 1024} kB) e o servidor ainda não aceitou — " +
                        "tento a cada 30 s e antes de finalizar"
                }
            }
        }
    }
    // VARREDURA DAS PENDENTES.
    LaunchedEffect(sessaoId) {
        val daSessao = sessaoId ?: return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(30_000)
            val paradas = withContext(Dispatchers.IO) { custodia.pendentesDaSessao(daSessao) }
            for ((id, arquivo) in paradas) {
                if (repassarFoto(id, arquivo, daSessao, 1)) {
                    fotosEnviadas += 1
                    status = "Foto atrasada subiu ao servidor ✓ (${arquivo.length() / 1024} kB)"
                }
            }
        }
    }
    // Liga o receptor quando a sessão abre no modo tablet; desliga quando fecha.
    LaunchedEffect(sessaoId, videoNoTablet) {
        if (sessaoId != null && videoNoTablet) {
            (device as? MentraGlassesDevice)?.perfilVideo =
                when (config.qualidadeVideoTablet) {
                    ConfiguracoesApp.QUALIDADE_1080P30 -> MentraGlassesDevice.PerfilVideo.TABLET_1080P30
                    ConfiguracoesApp.QUALIDADE_720P_FLUIDO -> MentraGlassesDevice.PerfilVideo.TABLET_720P_FLUIDO
                    ConfiguracoesApp.QUALIDADE_1080P_FLUIDO -> MentraGlassesDevice.PerfilVideo.TABLET_1080P_FLUIDO
                    else -> MentraGlassesDevice.PerfilVideo.TABLET_720P30
                }
            // Socket fora da thread principal (o Android barra bind/close na main).
            val erro = withContext(Dispatchers.IO) { receptor.ligar() }
            status = if (erro == null) "Receptor de vídeo do tablet ligado em ${receptor.estado.ip}:${receptor.estado.porta}"
                     else "Receptor de vídeo: $erro"
        } else {
            (device as? MentraGlassesDevice)?.perfilVideo =
                if (config.qualidadeVideoServidor == ConfiguracoesApp.QUALIDADE_SERVIDOR_FLUIDO)
                    MentraGlassesDevice.PerfilVideo.SERVIDOR_FLUIDO
                else MentraGlassesDevice.PerfilVideo.SERVIDOR
            if (sessaoId == null) withContext(Dispatchers.IO) { receptor.desligar() }
        }
    }
    /** URL que vai para os óculos: o tablet (modo teste) ou a VPS (como sempre). */
    // Lê receptorEstado (snapshot state) de propósito: é ele que faz a
    // composição recalcular a URL quando o receptor termina de subir.
    val urlDoStream: String? =
        if (videoNoTablet) sessaoId?.takeIf { receptorEstado.ligado }?.let { receptor.urlPara(it) } else rtmpUrl

    // "Visão dos óculos": o mesmo stream RTMP que os óculos mandam ao backend, devolvido como HTTP-FLV pelo node-media-server.
    val urlVisao = if (videoNoTablet) null else rtmpUrl?.let { r ->
        Regex("^rtmp://([^:/]+)(?::\\d+)?/(.+)$").find(r)?.let { m ->
            "http://${m.groupValues[1]}:8100/${m.groupValues[2]}.flv"
        }
    }

    var fichaLacre by remember { mutableStateOf<BackendClient.FichaLacre?>(null) }
    var lendoLacre by remember { mutableStateOf(false) }
    /** O que o Atena devolveu ao abrir a sessão — vira contexto do assistente IA. */
    var casoAtena by remember { mutableStateOf<BackendClient.CasoAtena?>(null) }
    LaunchedEffect(sessaoId) { if (sessaoId == null) casoAtena = null }
    // Sessão nova → a lista de narrações da tela começa do zero.
    LaunchedEffect(sessaoId) { if (sessaoId != null) narracoes = emptyList() }
    var textoRequisicao by remember { mutableStateOf<String?>(null) }
    var avisoRequisicao by remember { mutableStateOf<String?>(null) }
    /** true enquanto o documento está sendo baixado. */
    var requisicaoCarregando by remember { mutableStateOf(false) }
    var requisicaoInexistente by remember { mutableStateOf(false) }
    LaunchedEffect(casoAtena) {
        textoRequisicao = null
        avisoRequisicao = null
        requisicaoInexistente = false
        requisicaoCarregando = false
        val caso = casoAtena ?: return@LaunchedEffect
        val docId = caso.documentoId
        if (docId.isNullOrBlank()) {
            requisicaoInexistente = true
            avisoRequisicao = "este protocolo não tem requisição anexada no Atena"
            return@LaunchedEffect
        }
        requisicaoCarregando = true
        try {
            // Falha aqui não pode travar nada: sem o texto, o assistente segue
            // com o resto do contexto — mas sabendo que ficou sem a requisição.
            val r = backend.textoDocumento(docId)
            textoRequisicao = r.texto
            avisoRequisicao = if (r.texto == null) (r.aviso ?: "documento do Atena ilegível") else null
        } finally {
            requisicaoCarregando = false
        }
    }

    LaunchedEffect(ponteGemini, videoNoTablet, videoLigado, sessaoId) {
        if (ponteGemini == null || !videoNoTablet || !videoLigado || sessaoId == null) return@LaunchedEffect
        while (true) {
            val ponte = ponteGemini ?: break
            val jpeg = runCatching { quadroParaIa() }.getOrNull()
            if (jpeg != null) ponte.enviarQuadro(jpeg)
            delay(1_000)
        }
    }
    LaunchedEffect(ponteGemini, urlVisao) {
        ponteGemini?.definirVideo(urlVisao)
        // Cinto de segurança: enquanto o assistente não confirmar que está ENXERGANDO, reenvia a URL do vídeo a cada 20 s.
        while (ponteGemini != null && urlVisao != null && !iaEnxergando) {
            kotlinx.coroutines.delay(20_000)
            if (!iaEnxergando) ponteGemini?.definirVideo(urlVisao)
        }
    }
    LaunchedEffect(
        ponteGemini, fichaLacre, casoAtena, textoRequisicao, avisoRequisicao,
        requisicaoCarregando, protocolo,
        peritoDaSessao, matriculaNaoCadastrada,
        requisicaoInexistente,
    ) {
        if (ponteGemini == null) return@LaunchedEffect
        // Espera o documento decidir se existe ou não antes de abrir a boca.
        if (requisicaoCarregando) return@LaunchedEffect
        val f = fichaLacre
        val a = casoAtena
        val texto = buildString {
            append("CONTEXTO DO CASO (na PRIMEIRA vez, faça o resumo de abertura falado; se já o fez nesta sessão, responda apenas \"Contexto atualizado.\"): ")
            append("protocolo ${protocolo.trim().ifBlank { "não informado" }}.")
            matriculaNaoCadastrada?.let { mat ->
                append(" AVISO DE RESPONSABILIDADE: a matrícula $mat, digitada no tablet, NÃO está")
                append(" cadastrada no sistema. Esta perícia está registrada no nome de")
                append(" ${peritoDaSessao ?: "quem está logado no tablet"}, e é essa pessoa que vai")
                append(" assinar o laudo.")
            }
            peritoDaSessao?.takeIf { matriculaNaoCadastrada == null }?.let {
                append(" Perito responsável por esta perícia: $it.")
            }
            if (a != null) {
                a.autoridade?.let { append(" Nome do solicitante: $it.") }
                a.unidadeRequisitante?.let { append(" Unidade requisitante: $it.") }
                a.dataOcorrencia?.let { append(" Data da ocorrência/fato: $it.") }
                a.statusProtocolo?.let { append(" Status do protocolo: $it.") }
                a.areaAtuacao?.let { append(" Área de atuação: $it.") }
                if (a.envolvidos.isNotEmpty()) append(" Pessoa(s) envolvida(s): ${a.envolvidos.joinToString("; ")}.")
                a.documentoPrincipal?.let { append(" Documento principal: $it.") }
                a.prioridade?.let { append(" Prioridade: $it.") }
                a.prazoHoras?.let { append(" Prazo: $it horas.") }
                if (a.naturezas.isNotEmpty()) append(" Natureza do exame: ${a.naturezas.joinToString(", ")}.")
                if (a.materiais.isNotEmpty()) append(" Materiais do caso: ${a.materiais.joinToString("; ")}.")
                if (a.exames.isNotEmpty()) append(" Exames complementares: ${a.exames.joinToString("; ")}.")
            }
            val req = textoRequisicao
            if (req != null) {
                // RECORTE, não o texto integral.
                val recorte = req.take(LIMITE_REQUISICAO_BANCADA)
                append(" CONTEÚDO DO DOCUMENTO PRINCIPAL (requisição anexada no Atena; ")
                append("quando houver linhas \"===== ARQUIVO n/N \u2014 nome =====\", cada trecho é um ")
                append("arquivo do pacote — a requisição e seus anexos): ")
                append(recorte)
                if (req.length > recorte.length) {
                    append(" [...este é o início do documento (${recorte.length} de ${req.length} caracteres); ")
                    append("o restante está no laudo. Se o perito perguntar algo que não está aqui, diga que ")
                    append("essa parte do documento não veio para a bancada.]")
                }
            } else if (requisicaoInexistente) {
                // ROTINA, NÃO FALHA: o protocolo veio sem requisição anexada.
                append(" ESTE PROTOCOLO NÃO TEM REQUISIÇÃO ANEXADA no Atena. Isso é comum e NÃO é falha ")
                append("do sistema: não diga que houve erro, que algo falhou ou que não conseguiu abrir nada. ")
                append("Conduza pelo roteiro e pelos dados de cadastro acima. Se o perito perguntar o histórico ")
                append("dos fatos ou o que foi requisitado, diga com naturalidade que este protocolo veio sem ")
                append("requisição anexada e ofereça registrar o que ele ditar. NUNCA invente, resuma nem ")
                append("suponha o teor da requisição, do histórico do fato ou do boletim de ocorrência.")
            } else {
                append(" A REQUISIÇÃO DESTE PROTOCOLO NÃO PÔDE SER LIDA AGORA (motivo técnico registrado no ")
                append("servidor: ${avisoRequisicao ?: "documento do Atena indisponível"}). NÃO repita esse motivo ")
                append("técnico em voz alta e não use palavras como backend, servidor, erro de comunicação ou ")
                append("código. Você NÃO tem o que a autoridade pediu. Conduza pelo roteiro e pelos dados de ")
                append("cadastro acima. Se o perito perguntar o histórico dos fatos ou o que foi requisitado, ")
                append("diga que o documento não pôde ser aberto nesta sessão, que ele pode tentar de novo mais ")
                append("tarde, e ofereça registrar o que ele ditar. NUNCA invente, resuma nem suponha o teor da ")
                append("requisição, do histórico do fato ou do boletim de ocorrência.")
            }
            if (f != null) {
                append(" Lacre lido: ${f.codigo}.")
                f.solicitante?.let { append(" Solicitante (ficha do lacre): $it.") }
                f.unidadeRequisitante?.let { append(" Unidade requisitante: $it.") }
                f.vitima?.let { append(" Vítima: $it.") }
                f.dataOcorrencia?.let { append(" Data da ocorrência: $it.") }
                append(" Quantidade de materiais: ${f.quantidadeMateriais}.")
                if (f.materiais.isNotEmpty()) append(" Materiais (ficha): ${f.materiais.joinToString("; ")}.")
                if (f.naturezas.isNotEmpty()) append(" Naturezas (ficha): ${f.naturezas.joinToString(", ")}.")
            }
        }
        ponteGemini?.definirContexto(texto)
    }
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
                backend.login(MATRICULA_PADRAO, senhaPerito)
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
                    falarSeSemIa("Lacre lido. Protocolo ${ficha!!.numeroProtocolo}.")
                    status = "Lacre ${ficha!!.codigo} → protocolo ${ficha!!.numeroProtocolo}"
                }
            } catch (e: Exception) {
                status = "Leitura do lacre: ${e.message}"
            } finally {
                lendoLacre = false
            }
        }
    }

    var continuacaoPendente by remember { mutableStateOf<BackendClient.PericiaAnterior?>(null) }

    /** Login -> resolve o protocolo -> abre a sessao. */
    fun iniciarSessao(modo: String? = null) {
        if (ocupado) return
        continuacaoPendente = null
        escopo.launch {
            ocupado = true
            try {
                backend.baseUrl = enderecoBackend.trim()
                status = "Entrando no backend..."
                backend.login(MATRICULA_PADRAO, senhaPerito)
                status = "Resolvendo protocolo ${protocolo.trim()}..."
                val caso = backend.resolverProtocolo(protocolo.trim())
                casoAtena = caso
                val perfilId = backend.primeiroPerfil()
                status = "Abrindo sessão..."
                val resposta = backend.abrirSessao(caso.id, perfilId, matricula.trim(), modo)
                if (resposta is BackendClient.Abertura.Escolher) {
                    // Nada foi aberto: a tela pergunta e chama de novo com o modo.
                    continuacaoPendente = resposta.anterior
                    status = "Este protocolo já tem uma perícia sua finalizada — continuar ou começar outra?"
                    return@launch
                }
                val aberta = (resposta as BackendClient.Abertura.Aberta).sessao
                config.ultimaMatricula = matricula // lembra para a próxima perícia
                sessaoId = aberta.sessaoId
                rtmpUrl = aberta.rtmpUrl
                laudoId = null
                if (modo == "continuar" && aberta.retomada) {
                    fotosEnviadas = aberta.fotosRecebidas
                    quadrosMarcados = 0
                    falarSeSemIa("Perícia reaberta. O que você capturar agora entra no mesmo laudo.")
                    status = "Perícia reaberta — ${aberta.fotosRecebidas} foto(s) já no laudo; o que capturar agora entra junto"
                } else if (aberta.retomada) {
                    fotosEnviadas = aberta.fotosRecebidas
                    quadrosMarcados = 0
                    falarSeSemIa("Sessão retomada. Pode continuar de onde parou.")
                    status = "Sessão retomada — ${aberta.fotosRecebidas} foto(s) já na perícia; pode continuar"
                } else {
                    fotosEnviadas = 0
                    quadrosMarcados = 0
                    falarSeSemIa("Sessão iniciada. Pode capturar.")
                    status = "Sessão aberta — pode capturar"
                }
                // DE QUEM É A PERÍCIA.
                peritoDaSessao = aberta.peritoNome ?: aberta.peritoMatricula
                matriculaNaoCadastrada = aberta.matriculaDesconhecida
                if (aberta.matriculaDesconhecida != null) {
                    status = "ATENÇÃO: matrícula ${aberta.matriculaDesconhecida} não existe no sistema — " +
                        "a perícia ficou no nome de ${peritoDaSessao ?: "quem está logado no tablet"}"
                    // Pelo portão de UMA voz: com o assistente ligado, quem anuncia isso é a IA (regra do núcleo).
                    falarSeSemIa(
                        "Atenção: a matrícula digitada não existe no sistema. " +
                            "Esta perícia vai ficar no nome de outro usuário.",
                    )
                } else if (peritoDaSessao != null) {
                    status = "Perícia de $peritoDaSessao — ${if (aberta.retomada) "sessão retomada" else "pode capturar"}"
                }
            } catch (e: Exception) {
                status = "Erro no backend: ${e.message}"
            } finally {
                ocupado = false
            }
        }
    }

    /** Fecha a sessao; o backend monta o laudo. */
    fun finalizarSessao(aoTerminar: ((Boolean, String) -> Unit)? = null) {
        val id = sessaoId
        if (id == null) {
            aoTerminar?.invoke(false, "não há perícia aberta neste tablet")
            return
        }
        if (finalizando) {
            aoTerminar?.invoke(false, "o encerramento já está em andamento; aguarde")
            return
        }
        pedidoFinalizarMs = System.currentTimeMillis()
        finalizando = true
        // A partir daqui a IA não ouve mais ninguém: o encerramento leva minutos e ela respondia a quem falasse perto dos óculos.
        ponteGemini?.definirEncerrando(true)
        escopo.launch {
            // So devolve o `ocupado` se foi ESTE encerramento que o tomou —
            // senao liberaria a trava de outra operacao em curso.
            var tomouOcupado = false
            try {
                // Espera a vez (abrir sessao pode estar em curso) em vez de
                // devolver sem fazer nada.
                var esperou = 0
                while (ocupado && esperou < 20_000) {
                    status = "Aguardando a operação em curso para finalizar..."
                    kotlinx.coroutines.delay(250); esperou += 250
                }
                if (ocupado) {
                    status = "Não deu para finalizar: o app está ocupado."
                    vozFeedback.falar("Não consegui finalizar agora. Tente de novo.")
                    aoTerminar?.invoke(false, "o aplicativo está ocupado com outra operação; peça para o perito tentar de novo")
                    return@launch
                }
                ocupado = true
                tomouOcupado = true
                if (videoLigado) {
                    status = "Encerrando o vídeo dos óculos..."
                    // Falha aqui nao pode travar o encerramento: o RTMP cai sozinho quando a sessao fecha no servidor.
                    runCatching { device.pararVideo() }
                    videoLigado = false
                    kotlinx.coroutines.delay(1500)
                }
                if (videoNoTablet && enviarDepois) {
                    // Só espera o receptor FECHAR o último segmento (os óculos
                    // pararam de publicar); nada sobe agora.
                    var esperaFecho = 0
                    while (receptorEstado.publicando && esperaFecho < 15_000) { kotlinx.coroutines.delay(500); esperaFecho += 500 }
                    val mb = receptor.segmentosDe(id).sumOf { it.length() } / 1_000_000.0
                    filaEnvio.registrar(id, protocolo.trim().ifBlank { "perícia ${id.take(8)}" })
                    status = "Vídeo guardado no tablet (${"%.0f".format(mb)} MB) — sobe depois, em Vídeos para enviar"
                } else if (videoNoTablet) {
                    // Os óculos fecham a publicação, o receptor fecha o segmento e o upload começa sozinho.
                    kotlinx.coroutines.delay(2_500)
                    var esperaVideo = 0
                    while ((segmentosSubindo > 0 || receptorEstado.publicando) && esperaVideo < 180_000) {
                        val mb = receptor.segmentosDe(id).sumOf { it.length() } / 1_000_000.0
                        status = "Subindo o vídeo do tablet para o servidor (${"%.0f".format(mb)} MB restantes)..."
                        kotlinx.coroutines.delay(500); esperaVideo += 500
                    }
                    if (segmentosComFalha > 0 || receptor.segmentosDe(id).isNotEmpty()) {
                        // Isto não é só o vídeo: as fotos que os óculos não deram são recortadas DO VÍDEO no servidor.
                        falarSeSemIa("Atenção: parte do vídeo ficou no tablet. A perícia será finalizada mesmo assim, e o vídeo sobe sozinho quando o tablet tiver rede.")
                        status = "Vídeo: ${receptor.segmentosDe(id).size} segmento(s) ficaram no tablet — sobem sozinhos na próxima perícia aberta com rede, e o laudo ganha o vídeo"
                        kotlinx.coroutines.delay(2_000)
                    }
                }
                // FOTOS PARADAS NO TABLET.
                run {
                    var esperaFoto = 0
                    while (esperaFoto < 120_000) {
                        val paradas = withContext(Dispatchers.IO) { custodia.pendentesDaSessao(id) }
                        if (paradas.isEmpty()) break
                        status = "Subindo ${paradas.size} foto(s) que estavam no tablet..."
                        for ((rid, arquivo) in paradas) {
                            if (repassarFoto(rid, arquivo, id, 2)) fotosEnviadas += 1
                        }
                        kotlinx.coroutines.delay(2_000); esperaFoto += 2_000
                    }
                    val sobraram = withContext(Dispatchers.IO) { custodia.pendentesDaSessao(id).size }
                    if (sobraram > 0) {
                        vozFeedback.falar(
                            "Atenção: $sobraram foto não subiu ao servidor e ficou no tablet. " +
                                "A perícia será finalizada mesmo assim."
                        )
                        status = "$sobraram foto(s) ficaram no tablet e NÃO entram no laudo — confira no painel antes de assinar"
                        kotlinx.coroutines.delay(2_500)
                    }
                }
                status = "Finalizando sessão..."
                // Última fala ainda no buffer entra na narração antes do laudo.
                val ultimaFala = narracaoPendente.trim()
                narracaoPendente = ""
                if (ultimaFala.isNotBlank()) {
                    narracoes = narracoes + ultimaFala
                    runCatching { backend.narrar(id, ultimaFala) }
                }
                laudoId = backend.finalizarSessao(id, videoDepois = videoNoTablet && enviarDepois)
                if (videoNoTablet && enviarDepois) {
                    aoTerminar?.invoke(true, "sessão encerrada; o vídeo ficou guardado no tablet e o laudo será gerado quando ele for enviado ao servidor — diga isso ao perito")
                    status = "Sessão encerrada — o laudo sai quando o vídeo subir (cartão Vídeos para enviar)"
                    falarSeSemIa("Sessão finalizada. O laudo será gerado quando o vídeo for enviado.")
                } else {
                    aoTerminar?.invoke(true, "sessão encerrada; o laudo entrou em processamento")
                    status = "Laudo gerado — revise e baixe no site do PeritaVision"
                    falarSeSemIa("Sessão finalizada. Laudo em processamento.")
                }
                if (aoTerminar != null) {
                    val ponte = ponteGemini
                    kotlinx.coroutines.delay(1_200) // dá tempo de o áudio começar
                    var esperando = 0
                    while (ponte?.estaFalando() == true && esperando < 15_000) {
                        kotlinx.coroutines.delay(250); esperando += 250
                    }
                }
                // VOLTA PARA A PRIMEIRA TELA, pronta para a proxima pericia.
                sessaoId = null
                rtmpUrl = null
                protocolo = ""
                fichaLacre = null
                fotosEnviadas = 0
                quadrosMarcados = 0
                custodia.encerrarSessao()
                matriculaNaoCadastrada = null
                pedidoFinalizarMs = 0L
                bipe("conversa")
            } catch (e: Exception) {
                // A pericia CONTINUA ABERTA.
                ponteGemini?.definirEncerrando(false) // a IA volta a ouvir
                pedidoFinalizarMs = System.currentTimeMillis()
                val motivo = e.message ?: "erro desconhecido"
                status = "Erro ao finalizar: $motivo"
                vozFeedback.falar("Não consegui finalizar. A perícia continua aberta.")
                aoTerminar?.invoke(
                    false,
                    "NÃO encerrou — a perícia continua aberta. Motivo: $motivo. " +
                        "Diga isso ao perito em voz alta e pergunte se quer tentar de novo; " +
                        "a próxima chamada de finalizar_sessao já executa, sem confirmar outra vez.",
                )
            } finally {
                if (tomouOcupado) ocupado = false
                finalizando = false
            }
        }
    }

    // GALERIA DA BANCADA.
    LaunchedEffect(sessaoId, fotosEnviadas, quadrosMarcados) {
        val id = sessaoId
        if (id == null) { fotosDaPericia = emptyList(); return@LaunchedEffect }
        var tentativas = 0
        while (tentativas < 12) {
            val lista = runCatching { backend.listarFotos(id) }.getOrNull()
            if (lista != null) {
                for (meta in lista) {
                    // takeIf: quem falhou o download fica com miniatura nula e é
                    // TENTADA DE NOVO. Sem isso a foto ficava "…" para sempre.
                    val existente = fotosDaPericia.firstOrNull { it.id == meta.id }?.takeIf { it.miniatura != null }
                    if (existente != null) continue
                    val bmp = withContext(Dispatchers.IO) {
                        runCatching { miniaturaDe(backend.baixarFoto(meta.id)) }.getOrNull()
                    }
                    val nova = FotoNaTela(
                        id = meta.id,
                        miniatura = bmp,
                        doVideo = meta.origem == "quadro_do_video",
                        kb = (meta.bytes / 1024).toInt(),
                        requestId = meta.requestId,
                    )
                    fotosDaPericia = (
                        fotosDaPericia.filterNot { f ->
                            f.id == meta.id ||
                                (meta.requestId != null && f.requestId == meta.requestId)
                        } + nova
                        ).sortedBy { f -> lista.indexOfFirst { it.id == f.id }.takeIf { it >= 0 } ?: 999 }
                }
                // Chegou tudo o que o app contou como FOTO (quadro marcado só
                // vira imagem depois, no servidor) e nada ficou em branco.
                val fotosReais = fotosDaPericia.count { !it.doVideo }
                if (fotosReais >= fotosEnviadas && fotosDaPericia.all { it.miniatura != null }) break
            }
            tentativas += 1
            kotlinx.coroutines.delay(if (tentativas < 4) 3_000 else 20_000)
        }
    }

    // Injeta no Mentra COMO pedir autorizacao de captura ao backend.
    LaunchedEffect(device) {
        (device as? MentraGlassesDevice)?.obterAutorizacao = {
            sessaoId?.let { id ->
                val c = backend.solicitarCaptura(id)
                val idSeguro = Regex("^[A-Za-z0-9_-]{4,80}$").matches(c.requestId)
                val noTablet = if (idSeguro && custodia.deveUsarTablet()) receptorFotos.urlPara(c.requestId) else null
                if (noTablet == null) {
                    MentraGlassesDevice.AutorizacaoCaptura(c.requestId, c.webhookUrl, c.authToken)
                } else {
                    custodia.guardar(
                        br.com.facilmova.peritavision.custodia.CustodiaDeFotos.Credencial(
                            sessaoId = id, requestId = c.requestId,
                            webhookUrl = c.webhookUrl, authToken = c.authToken,
                        )
                    )
                    escopo.launch {
                        kotlinx.coroutines.delay(60_000)
                        val houveConexao = receptorFotosEstado.conexoes > 0
                        if (sessaoId == id && custodia.credencial(c.requestId, id) != null &&
                            custodia.desistirDaFoto(c.requestId, houveConexao)
                        ) {
                            if (!houveConexao) {
                                status = "Os óculos não chegaram nem a abrir conexão no tablet " +
                                    "(${receptorFotosEstado.ip}:${receptorFotosEstado.porta}). " +
                                    "As próximas fotos vão direto ao servidor, como antes. " +
                                    "Marquei o quadro no vídeo desta."
                            } else {
                                status = "A foto não chegou ao tablet em 60 s (última resposta da porta: " +
                                    "${receptorFotosEstado.ultimoResultado ?: "nenhuma"}) — marquei o quadro " +
                                    "no vídeo; se ela chegar depois, entra como foto e a marca cai"
                            }
                        }
                    }
                    MentraGlassesDevice.AutorizacaoCaptura(c.requestId, noTablet, peloTablet = true, authToken = c.authToken)
                }
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
                    // Caiu o BLE/Wi-Fi: o stream morreu com ele.
                    if (!evento.conectado) videoLigado = false
                    if (!evento.conectado) {
                        // O BLE caiu e com ele o que sabíamos da Wi-Fi dos óculos.
                        wifiOculos = false
                        ssidOculos = null
                        wifiEnviadaNestaConexao = false
                    }
                    status = if (evento.conectado) "Óculos conectado" else "Óculos desconectado"
                    // WI-FI AUTOMÁTICO: conectou e há rede salva → envia sem pedir nada.
                    if (evento.conectado && wifiSsid.isNotBlank()) {
                        escopo.launch {
                            delay(2_500)
                            if (conectado && !wifiOculos && !wifiEnviadaNestaConexao) {
                                wifiEnviadaNestaConexao = true
                                status = "Enviando a Wi-Fi salva (${wifiSsid.trim()}) aos óculos..."
                                (device as? MentraGlassesDevice)?.configurarWifi(wifiSsid.trim(), wifiSenha)
                                delay(40_000)
                                if (conectado && !wifiOculos && wifiSsid.isNotBlank()) {
                                    status = "Óculos ainda sem Wi-Fi — reenviando a rede salva (${wifiSsid.trim()})..."
                                    (device as? MentraGlassesDevice)?.configurarWifi(wifiSsid.trim(), wifiSenha)
                                }
                            }
                        }
                    }
                }
                is GlassesEvent.Wifi -> {
                    wifiOculos = evento.conectado
                    ssidOculos = evento.ssid
                    status = if (evento.conectado)
                        "Óculos na Wi-Fi ${evento.ssid ?: ""} ✓"
                    else "Óculos SEM Wi-Fi — a foto não chega ao servidor"
                    // Os óculos avisaram que estão SEM rede depois de conectar (desligaram e perderam a Wi-Fi): manda a salva, uma vez.
                    if (!evento.conectado && conectado && wifiSsid.isNotBlank() && !wifiEnviadaNestaConexao) {
                        wifiEnviadaNestaConexao = true
                        status = "Óculos sem Wi-Fi — enviando a rede salva (${wifiSsid.trim()})..."
                        (device as? MentraGlassesDevice)?.configurarWifi(wifiSsid.trim(), wifiSenha)
                    }
                }
                is GlassesEvent.GravacaoIniciada ->
                    status = "Gravando ${evento.tipo.name.lowercase()}..."
                is GlassesEvent.Erro -> {
                    conectando = false
                    status = "Erro: ${evento.mensagem}"
                }
                is GlassesEvent.Aviso -> status = evento.mensagem
                is GlassesEvent.TranscricaoIndisponivel -> {
                    // Os oculos ouvem mas nao transcrevem: assume o microfone do celular, sem o perito precisar fazer nada.
                    if (ponteGemini == null) {
                        usarVozDoCelular = true
                        voz.iniciar()
                        vozAtiva = true
                    }
                }
                is GlassesEvent.CapturaRemota -> {
                    // Os oculos subiram o JPEG direto ao webhook; o backend selou.
                    if (evento.fotoDeVerdade) {
                        fotosEnviadas++
                        status = "Foto $fotosEnviadas enviada ao backend ✓"
                        // Pede a descrição EM VOZ, pelos óculos (se pareados como áudio Bluetooth; senão, pelo celular).
                        falarSeSemIa("Foto capturada. Descreva a evidência.")
                    } else {
                        // MARCA no vídeo, não foto.
                        quadrosMarcados++
                        status = "Quadro $quadrosMarcados MARCADO no vídeo — não é foto; o servidor tenta recortar no fim"
                        falarSeSemIa("Não consegui a foto. Marquei o quadro no vídeo. Descreva a evidência.")
                    }
                }
                is GlassesEvent.FotoRecusada -> {
                    // Os óculos não fotografam enquanto transmitem ("Camera busy with streaming").
                    val id = sessaoId
                    val jpeg = if (id != null) quadroDoVisor() else null
                    if (id == null || jpeg == null) {
                        quadrosMarcados++
                        status = "Os óculos não deram a foto (${evento.motivo}) e o tablet não tem a imagem — " +
                            "marquei o quadro no vídeo; o servidor tenta recortar no fim"
                        falarSeSemIa("Não consegui a foto. Marquei o quadro no vídeo. Descreva a evidência.")
                    } else {
                        if (custodia.credencial(evento.requestId, id) == null) {
                            // Foto que ia direto ao servidor: a credencial não passou pela custódia.
                            custodia.guardar(
                                br.com.facilmova.peritavision.custodia.CustodiaDeFotos.Credencial(
                                    sessaoId = id, requestId = evento.requestId,
                                    webhookUrl = evento.webhookUrl, authToken = evento.authToken,
                                )
                            )
                        }
                        val arquivo = withContext(Dispatchers.IO) { custodia.gravarFoto(evento.requestId, jpeg, "image/jpeg") }
                        if (arquivo == null) {
                            quadrosMarcados++
                            status = "Recortei o quadro do visor mas não consegui gravá-lo no tablet — marquei o quadro no vídeo"
                        } else {
                            val kb = jpeg.size / 1024
                            fotosDaPericia = fotosDaPericia + FotoNaTela(
                                id = "tablet:${evento.requestId}", miniatura = miniaturaDe(jpeg), doVideo = true,
                                kb = kb, requestId = evento.requestId,
                            )
                            status = "Câmera dos óculos ocupada com o vídeo — recortei o quadro do visor ($kb kB) e estou subindo como a captura"
                            if (repassarFoto(evento.requestId, arquivo, id, 4)) {
                                fotosEnviadas += 1
                                status = "Quadro do vídeo no servidor como captura $fotosEnviadas ✓ " +
                                    "($kb kB, ${decodificadorEstado.largura}x${decodificadorEstado.altura}) — os óculos não fotografam enquanto transmitem"
                                falarSeSemIa("Registrei o quadro do vídeo. Descreva a evidência.")
                            } else {
                                status = "Quadro recortado ESTÁ NO TABLET ($kb kB) e o servidor ainda não aceitou — tento a cada 30 s e antes de finalizar"
                            }
                        }
                    }
                }
                is GlassesEvent.ArquivoCapturado -> {
                    // Modo PHONE: o arquivo esta no celular.
                    status = "Selando custódia..."
                    val ev = withContext(Dispatchers.IO) {
                        selar.selar(evento.tipo, evento.arquivo, protocolo.trim().ifBlank { null })
                    }
                    ultima = ev
                    status = "Evidência selada (${ev.tipo.name})"
                    if (evento.tipo == TipoEvidencia.FOTO) {
                        falarSeSemIa("Foto capturada. Descreva a evidência.")
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
                            falarSeSemIa("Falha ao enviar ao servidor")
                        }
                    }
                }
            }
        }
    }

    // Comando de voz pelos MICROFONES DOS OCULOS (transcricao local, sem nuvem).
    LaunchedEffect(device, ponteGemini) {
        // Com o assistente IA ligado, QUEM manda é o Gemini (função capturar_foto).
        (device as? MentraGlassesDevice)?.onComandoVoz =
            if (ponteGemini != null) null else ({ device.capturarFoto() })
    }

    /** Liga/desliga a escuta. */
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

    /** Escuta CONTINUA enquanto a sessao estiver aberta. */
    LaunchedEffect(sessaoId, conectado, usarVozDoCelular, ponteGemini) {
        val mentra = device as? MentraGlassesDevice
        val pelosOculosPossivel = mentra != null && !usarVozDoCelular
        val deveEscutar = sessaoId != null &&
            (if (pelosOculosPossivel) conectado else ponteGemini == null)
        val pelosOculos = pelosOculosPossivel
        if (deveEscutar && !vozAtiva) {
            if (pelosOculos) mentra.iniciarComandoDeVoz() else voz.iniciar()
            vozAtiva = true
        } else if (!deveEscutar && vozAtiva) {
            if (pelosOculos) mentra?.pararComandoDeVoz() else voz.encerrar()
            vozAtiva = false
        }
    }

    /** ÁUDIO DOS ÓCULOS → BACKEND → COMANDO. */
    LaunchedEffect(ponteGemini) {
        ponteGemini?.onComando = { id, nome, args ->
            when (nome) {
                "controlar_tela" -> {
                    val apagar = args.optString("acao") == "apagar"
                    telaApagada = apagar
                    ponteGemini?.responderComando(id, nome, true, if (apagar) "tela apagada" else "tela acesa")
                }
                "capturar_foto" -> {
                    val agora = System.currentTimeMillis()
                    when {
                        sessaoId == null ->
                            ponteGemini?.responderComando(id, nome, false, "captura indisponível (sessão não aberta)")
                        agora - ultimaCapturaMs < 6_000 ->
                            ponteGemini?.responderComando(id, nome, true, "a foto já foi capturada agora mesmo pelo comando de voz; não repita")
                        else -> {
                            ultimaCapturaMs = agora
                            device.capturarFoto()
                            ponteGemini?.responderComando(id, nome, true, "captura solicitada aos óculos")
                        }
                    }
                }
                "finalizar_sessao" -> {
                    // DOIS TEMPOS, TRAVADO EM CÓDIGO.
                    val agora = System.currentTimeMillis()
                    if (agora - pedidoFinalizarMs > 120_000L) {
                        pedidoFinalizarMs = agora
                        ponteGemini?.responderComando(
                            id, nome, false,
                            "A SESSÃO CONTINUA ABERTA — nada foi encerrado. Faça exatamente isto: " +
                                "(1) pergunte em voz alta \"Confirma o encerramento da sessão? Isso " +
                                "fecha a perícia e gera o laudo\"; (2) quando o perito confirmar, " +
                                "CHAME finalizar_sessao DE NOVO — é a segunda chamada que encerra. " +
                                "PROIBIDO dizer que a sessão foi finalizada: só anuncie encerramento " +
                                "quando esta função devolver ok=true.",
                        )
                    } else {
                        pedidoFinalizarMs = 0L
                        finalizarSessao { ok, detalhe ->
                            ponteGemini?.responderComando(id, nome, ok, detalhe)
                        }
                    }
                }
                else -> ponteGemini?.responderComando(id, nome, false, "função desconhecida")
            }
        }
    }

    var estavaPausado by remember { mutableStateOf(false) }
    LaunchedEffect(sessaoId, conectado, urlDoStream, gravacaoPausada) {
        val url = urlDoStream
        val id = sessaoId
        if (id != null && !videoLigado && url != null && !gravacaoPausada &&
            (device !is MentraGlassesDevice || conectado)
        ) {
            val retomando = estavaPausado
            // Saindo da pausa: dá tempo de o stopStream anterior assentar nos óculos.
            if (retomando) kotlinx.coroutines.delay(1_500)
            device.iniciarVideo(url)
            videoLigado = true
            if (retomando) {
                status = "Gravação retomada — vídeo dos óculos religado."
                runCatching { backend.registrarEvento(id, "marcador", "retomada", "voz") }
            }
        }
        estavaPausado = gravacaoPausada
    }

    LaunchedEffect(gravacaoPausada) {
        if (!gravacaoPausada) return@LaunchedEffect
        val id = sessaoId ?: return@LaunchedEffect
        if (videoLigado) {
            runCatching { device.pararVideo() }
            videoLigado = false
        }
        status = "Gravação em pausa — vídeo dos óculos parado. Diga a palavra de volta para continuar."
        runCatching { backend.registrarEvento(id, "marcador", "pausa", "voz") }
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
            onStatus = { msg ->
                val avisoDoAsr = msg.startsWith("Serviço de voz indisponível")
                if (!(avisoDoAsr && ponteGemini != null)) status = msg
            }
            onComando = { intencao, ouvido ->
                // Terceiro caminho offline (ASR do backend).
                if (ponteGemini == null) {
                    status = "Comando \"$ouvido\" → $intencao"
                    when (intencao) {
                        "CAPTURAR" -> {
                            ultimaCapturaMs = System.currentTimeMillis()
                            falarSeSemIa("Capturando"); device.capturarFoto()
                        }
                        "FINALIZAR" -> finalizarSessao()
                        // MARCAR e DESCARTAR entram junto com a narração do laudo.
                    }
                }
            }
        }
        streamer.conectar()
        audioStreamer = streamer
        // Cada frame do microfone dos óculos segue direto para o servidor.
        mentra.onPcm = { pcm, _ ->
            // Meia-duplex: enquanto o assistente fala pelo alto-falante dos óculos, o microfone capta a própria voz da IA.
            val iaFalando = ponteGemini?.estaFalando() == true
            if (!iaFalando) {
                if (!gravacaoPausada) streamer.enviarPcm(pcm)
                ponteGemini?.enviarPcm(pcm)
                canalAssistido?.enviarPcm(pcm)
            }
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
            runCatching { receptorFotos.desligar() }
            runCatching { receptor.desligar() }
            runCatching { decodificador.encerrar() }
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
                // O SDK da Mentra LANÇA exceção se o Bluetooth do aparelho estiver desligado ("Turn on phone Bluetooth to scan...").
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

    LaunchedEffect(temPermissoes) {
        if (temPermissoes && ehMentra && !conectado && !conectando) conectarOculos()
    }

    // A PARTIR DAQUI É SÓ APRESENTAÇÃO.

    val temSessao = sessaoId != null
    val ouvindoPelosOculos = vozAtiva && ehMentra && conectado && !usarVozDoCelular

    // Passo atual: define qual cartão sobe ao topo e ganha borda de destaque.
    val passo = when {
        ehMentra && !conectado -> Passo.CONECTAR
        !temSessao -> Passo.SESSAO
        else -> Passo.CAPTURAR
    }

    val motivoBloqueio = when {
        podeCapturar -> null
        ehMentra && !conectado -> "Conecte os óculos para liberar a captura."
        !hardwarePronto -> "Conceda as permissões de câmera e microfone."
        else -> "Abra a perícia no passo 3 — a foto precisa de um token emitido pelo servidor."
    }

    // Tom da barra de status.
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

    val numeroPericia = 1
    val apoioPreparo = when {
        ehMentra && !conectado -> "óculos desconectados — conecte em Configurações"
        ehMentra && !wifiOculos -> "Wi-Fi dos óculos pendente — em Configurações"
        !ehMentra && !temPermissoes -> "conceda câmera e microfone"
        else -> "pronto para abrir"
    }

    val cartaoOculos: @Composable () -> Unit = {
        CartaoOculos(
            numero = 1,
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
            numero = 2,
            destaque = !wifiOculos && conectado,
            conectado = conectado,
            wifiOculos = wifiOculos,
            ssidOculos = ssidOculos,
            ssid = wifiSsid,
            redeDoTablet = redeDoTablet,
            onSsid = { wifiSsid = it; config.wifiSsid = it },
            senha = wifiSenha,
            onSenha = { wifiSenha = it; config.wifiSenha = it },
            onEnviar = {
                (device as? MentraGlassesDevice)
                    ?.configurarWifi(wifiSsid.trim(), wifiSenha)
            },
        )
    }
    val cartaoServidor: @Composable () -> Unit = {
        CartaoServidor(
            numero = numeroPericia,
            bloqueado = !hardwarePronto,
            destaque = passo == Passo.SESSAO,
            matricula = matricula,
            onMatricula = { matricula = it },
            // Leitura de lacre agora é PELOS ÓCULOS (o scanner da câmera do tablet saiu).
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
            onFinalizar = { finalizarSessao { ok, detalhe -> if (!ok) vozFeedback.falar("Não encerrou. $detalhe") } },
        )
    }
    val cartaoCaptura: @Composable () -> Unit = {
        CartaoCaptura(
            destaque = passo == Passo.CAPTURAR,
            podeCapturar = podeCapturar,
            motivoBloqueio = motivoBloqueio,
            fotosEnviadas = fotosEnviadas,
            quadrosMarcados = quadrosMarcados + resumoFotos.marcadasNoVideo,
            fotosParadasNoTablet = resumoFotos.paradasNoTablet,
            rotaDaFoto = when {
                !resumoFotos.usandoTablet -> "A foto vai direto ao servidor (o tablet não recebeu)."
                receptorFotosEstado.ligado && receptorFotosEstado.recebidas > 0 ->
                    "A foto chega no tablet (${receptorFotosEstado.recebidas} até agora) e o tablet repassa ao servidor."
                receptorFotosEstado.ligado && receptorFotosEstado.conexoes > 0 ->
                    "Os óculos acharam o tablet; aguardando a foto chegar."
                receptorFotosEstado.ligado ->
                    "Aguardando a foto dos óculos."
                else -> "A foto vai direto ao servidor (receptor do tablet desligado)."
            },
            vozAtiva = vozAtiva,
            ouvindoPelosOculos = ouvindoPelosOculos,
            assistenteIa = ponteGemini != null,
            gravandoAudio = gravandoAudio,
            previewCamera = previewCamera,
            onFoto = { device.capturarFoto() },
            onVoz = { alternarComandoDeVoz() },
            onAudio = {
                if (gravandoAudio) device.pararAudio() else device.iniciarAudio()
                gravandoAudio = !gravandoAudio
            },
            onFinalizar = if (temSessao) ({ finalizarSessao { ok, detalhe -> if (!ok) vozFeedback.falar("Não encerrou. $detalhe") } }) else null,
            finalizando = ocupado || finalizando,
        )
    }
    // O que os óculos estão vendo, ao vivo (só no modo MENTRA — no PHONE a
    // pré-visualização da câmera já mora dentro do cartão de captura).
    val cartaoVisao: @Composable () -> Unit = {
        if (ehMentra) CartaoVisaoOculos(
            urlFlv = urlVisao, aoVivo = videoLigado, protocolo = protocolo.trim(),
            receptor = if (videoNoTablet) receptorEstado else null,
            perfil = (device as? MentraGlassesDevice)?.perfilVideo?.nome ?: "",
            subindo = segmentosSubindo, subidos = segmentosSubidos, comFalha = segmentosComFalha,
            imagem = if (videoNoTablet) ({ VisorComMarcacao(decodificador, marcacaoDaPerita) { visorAoVivo = it } }) else null,
            imagemEstado = if (videoNoTablet) decodificadorEstado else null,
        )
    }
    val cartaoAssistida: @Composable () -> Unit = {
        if (temSessao && ehMentra && videoNoTablet) CartaoPericiaAssistida(
            ativa = canalAssistido != null,
            codigo = codigoSalaAssistida,
            peritaOnline = peritaOnline,
            instrucao = instrucaoAssistida,
            mudo = assistidaMudo,
            status = assistidaStatus,
            ocupado = ocupado,
            onChamar = { abrirSalaAssistida() },
            onFeito = {
                instrucaoAssistida?.let { canalAssistido?.feito(it.id, true) }
                instrucaoAssistida = null
            },
            onNaoConsigo = {
                instrucaoAssistida?.let { canalAssistido?.feito(it.id, false) }
                instrucaoAssistida = null
            },
            onSilenciar = {
                assistidaMudo = !assistidaMudo
                canalAssistido?.mudo = assistidaMudo
            },
            onEncerrar = {
                val sid = sessaoId
                encerrarSalaAssistida(avisarServidor = true)
                if (sid != null) escopo.launch { runCatching { backend.encerrarAssistida(sid) } }
                status = "Sala encerrada. O assistente de IA pode ser religado no cartão do assistente."
            },
        )
    }
    // FOTOS DA PERÍCIA, embaixo do vídeo: miniaturas do que o servidor já tem.
    val cartaoFotos: @Composable () -> Unit = {
        if (temSessao) CartaoFotosDaPericia(
            fotos = fotosDaPericia,
            esperando = (fotosEnviadas - fotosDaPericia.count { !it.doVideo }).coerceAtLeast(0) +
                resumoFotos.aguardandoRede,
            onAmpliar = { fotoAmpliada = it },
        )
    }
    // O laudo em preenchimento: acompanha a sessão, seção a seção, com o que
    // já se sabe (ATENA, ficha do lacre, fotos seladas, narração do perito).
    val cartaoLaudo: @Composable () -> Unit = {
        if (temSessao) CartaoLaudoEmPreenchimento(
            protocolo = protocolo.trim(),
            caso = casoAtena,
            ficha = fichaLacre,
            fotosSeladas = fotosEnviadas,
            narracoes = narracoes,
            achados = achados,
        )
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
            enxergando = iaEnxergando,
            olhandoAgora = iaOlhandoAgora,
            trilha = iaTrilha,
            perguntandoTrilha = iaPerguntandoTrilha,
            modo = iaModo,
            onModo = { m ->
                if (ponteGemini != null) ponteGemini?.definirModo(m)
                else { iaModo = m; gravacaoPausada = m == "pausa" }
            },
            voz = iaVoz,
            perito = iaPerito,
            resposta = iaResposta,
            onAlternar = { alternarAssistenteIa() },
        )
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            Modifier
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding()
        ) {
            BarraDeTopo(
                titulo = "PeritaVision",
                // O nome do responsável na barra de topo: o perito confere de relance que a perícia é dele antes de começar a trabalhar.
                subtitulo = if (temSessao) {
                    "Protocolo ${protocolo.trim()} · ${peritoDaSessao ?: "sessão aberta"}"
                } else SLOGAN_APP,
                logo = R.drawable.logo_politec,
                onConfiguracoes = { mostrarConfiguracoes = true },
            )
        }
        FaixaProntidao(prontidao)

        Column(
            modifier = Modifier
                .weight(1f)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            if (!temSessao) {
                TituloSecao("Antes de começar", apoioPreparo)
                cartaoFichaLacre()
                cartaoServidor()
                CartaoEnviosPendentes(
                    pendentes = enviosPendentes,
                    enviando = enviando,
                    progresso = progressoEnvio,
                    onEnviar = { enviarPendentes("manual") },
                )
                cartaoEvidencia()
            } else {
                cartaoAssistida()
                cartaoVisao()
                cartaoFotos()
                cartaoCaptura()
                cartaoLaudo()
                cartaoAssistente()
                cartaoEvidencia()
            }
            RodapeAssinatura()
        }

        // Rodapé de mensagem único: a pílula acompanha o tom do status.
        Column(
            Modifier
                .background(MaterialTheme.colorScheme.background)
                .navigationBarsPadding()
        ) {
            BarraDeStatus(status, tomStatus)
        }
    }
    fotoAmpliada?.let { f ->
        FotoAmpliada(f, emAlta = bitmapAmpliado) { fotoAmpliada = null }
    }
    if (telaApagada) {
        Box(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black)
                .clickable { telaApagada = false },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Tela apagada — diga \"PeritaVision, acende a tela\" ou toque aqui",
                color = androidx.compose.ui.graphics.Color(0xFF3A3A3A),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    // Pergunta da PERÍCIA ANTERIOR: só aparece quando o servidor encontrou uma finalizada há pouco.
    continuacaoPendente?.let { ant ->
        val quando = dataLegivel(ant.finalizadaEm)?.let { "em $it" } ?: "há pouco"
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { continuacaoPendente = null },
            title = { Text("Este protocolo já tem uma perícia sua") },
            text = {
                Text(
                    "Finalizada $quando, com ${ant.fotosRecebidas} foto(s). " +
                        "Continuar acrescenta ao MESMO laudo — para quando faltou uma foto ou um material. " +
                        "Nova perícia começa do zero, com outro laudo.",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { iniciarSessao("continuar") }) {
                    Text("Continuar essa perícia")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { iniciarSessao("nova") }) {
                    Text("Nova perícia")
                }
            },
        )
    }
    if (mostrarConfiguracoes) {
        TelaConfiguracoes(
            config = config,
            urlPonte = BuildConfig.PV_PONTE_URL,
            onVoltar = { mostrarConfiguracoes = false },
            secaoOculos = {
                cartaoOculos()
                cartaoWifi()
            },
        )
    }
    }
}
