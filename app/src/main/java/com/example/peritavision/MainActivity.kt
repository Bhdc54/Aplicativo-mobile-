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
import com.example.peritavision.data.ConfiguracoesApp
import com.example.peritavision.ui.TelaConfiguracoes
import com.example.peritavision.net.BackendClient
import com.example.peritavision.ui.AvisoEscuta
import com.example.peritavision.ui.BarraDeStatus
import com.example.peritavision.ui.BarraDeTopo
import com.example.peritavision.ui.BotaoContorno
import com.example.peritavision.ui.BotaoPrimario
import com.example.peritavision.ui.BotaoTonal
import com.example.peritavision.ui.CabecalhoCartao
import com.example.peritavision.ui.CampoPv
import com.example.peritavision.ui.BalaoConversa
import com.example.peritavision.ui.BarraProgresso
import com.example.peritavision.ui.LinhaCampo
import com.example.peritavision.ui.SecaoLaudoPv
import com.example.peritavision.ui.CartaoPasso
import com.example.peritavision.ui.CartaoPv
import com.example.peritavision.ui.MolduraVisor
import com.example.peritavision.ui.TituloSecao
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Marca ───────────────────────────────────────────────────────────────────
// A logo em si é um arquivo: res/drawable/logo.xml. Para trocar pela logo real,
// leia as instruções que estão dentro daquele arquivo — não precisa vir aqui.
/** Quanto da requisição vai para a sessão da BANCADA (a IA que fala com o
 *  perito). O laudo continua recebendo o documento inteiro: aqui o limite
 *  existe porque o contexto viaja como UM turno de texto da sessão Live, e um
 *  turno gigante consome a janela de contexto e emudece o modelo. */
private const val LIMITE_REQUISICAO_BANCADA = 8000

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
// PV_PROTOCOLO (local.properties) NÃO preenche mais a tela: o campo começa
// vazio — o protocolo de teste aparecendo em toda abertura induzia a erro.

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

    // ── Assistente IA de bancada (ponte Gemini Live) — AUTOMÁTICO na sessão.
    // Liga sozinho quando a sessão de perícia abre e desliga quando ela fecha
    // (ver o LaunchedEffect logo depois da declaração de sessaoId). O toque no
    // cartão vira o desliga/religa manual do perito. Com a ponte ativa, uma
    // CÓPIA do áudio dos óculos vai ao Gemini (aviso de custódia em
    // PonteGemini.kt); o caminho offline oficial continua intacto em paralelo.
    // (Declarado antes do VoiceTrigger porque as falas do app consultam
    // ponteGemini — função/variável local só é visível abaixo da declaração.)
    var ponteGemini by remember { mutableStateOf<PonteGemini?>(null) }
    // Aba Configurações (engrenagem): roteiro fixo ou "perguntar", e modelo.
    val config = remember { ConfiguracoesApp(context) }
    var mostrarConfiguracoes by remember { mutableStateOf(false) }
    /** Roteiro em uso na sessão de trabalho ("Trilha A — Faca…"); null = ainda
     *  não definido (a IA está perguntando, ou o assistente está desligado). */
    var iaTrilha by remember { mutableStateOf<String?>(null) }
    var iaPerguntandoTrilha by remember { mutableStateOf(false) }
    /** MODO da IA: conversa | silencio | pausa (troca por palavra; toque é reserva). */
    var iaModo by remember { mutableStateOf("conversa") }
    /** QUEM MANDA NA GRAVAÇÃO. Separado do iaModo de propósito: o iaModo é
     *  espelho do servidor, e o servidor devolve "conversa" toda vez que a
     *  sessão do Gemini (re)abre — reconexão de Wi-Fi ou redeploy da ponte
     *  cancelavam a pausa sozinhos, religavam o vídeo e gravavam um marcador
     *  de "retomada por voz" que ninguém pediu (revisão 04/09). Só troca de
     *  modo pedida pelo perito (voz ou toque) mexe aqui. */
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
    /** TELA APAGADA por comando de voz ("PeritaVision, apaga a tela") — para
     *  luz forense com a sala escura. Não é o desligar do Android: o app segue
     *  em primeiro plano (áudio, vídeo e narração continuam); a tela vira um
     *  painel preto com brilho mínimo. "Acende a tela" ou um toque voltam. */
    var telaApagada by remember { mutableStateOf(false) }
    var iaPerito by remember { mutableStateOf("") }
    /** Quando a última captura foi disparada (qualquer via). Trava a FOTO EM
     *  DOBRO: a frase "assistente, capture uma foto disso" contém a palavra
     *  "capturar", então o comando offline dispara E o Gemini chama a função
     *  capturar_foto — sem esta trava saíam duas fotos da mesma cena. */
    var ultimaCapturaMs by remember { mutableStateOf(0L) }
    /** Quando a IA pediu para encerrar pela 1ª vez (aguardando confirmação). */
    var pedidoFinalizarMs by remember { mutableStateOf(0L) }
    /** Encerramento em curso — trava propria, para o pedido nao ser engolido
     *  pelo `ocupado` de outra operacao (ver finalizarSessao). */
    var finalizando by remember { mutableStateOf(false) }
    /** Fala do perito acumulada para a NARRAÇÃO do laudo (tudo vai; o cartão
     *  na tela mostra só as perguntas com o chamado "PeritaVision"). */
    var narracaoPendente by remember { mutableStateOf("") }
    // NARRAÇÕES DA BANCADA: cópia local do que já foi gravado no backend
    // (pericia.trecho_narracao). Alimenta, na tela, a seção "Considerações"
    // do laudo em preenchimento — o laudo final continua sendo montado pelo
    // servidor no Finalizar. Zera quando uma sessão nova abre.
    var narracoes by remember { mutableStateOf<List<String>>(emptyList()) }
    var narracaoUltimoMs by remember { mutableStateOf(0L) }
    /** true enquanto a fala em curso contém o chamado — controla a exibição. */
    var falaComChamado by remember { mutableStateOf(false) }
    var iaResposta by remember { mutableStateOf("") }
    /** true quando o servidor confirma que o vídeo dos óculos chegou ao
     *  Gemini. Sem isso o assistente responde "no escuro" — e já chamou um
     *  celular de peça íntima. O cartão mostra isso para o perito saber se
     *  pode confiar no que ele diz estar vendo. */
    var iaEnxergando by remember { mutableStateOf(false) }
    /** true só durante a janela em que a IA está realmente OLHANDO. */
    var iaOlhandoAgora by remember { mutableStateOf(false) }

    /** true quando o perito DESLIGOU o assistente à mão nesta sessão. */
    var iaDesligadaManual by remember { mutableStateOf(false) }

    /** UMA voz só na bancada. Quem fala é o Gemini sempre que o assistente
     *  existe — mesmo nos segundos em que ele ainda está conectando (checar
     *  ponteGemini == null aqui criava a corrida das DUAS VOZES na abertura:
     *  o TTS anunciava a sessão e o resumo do Gemini vinha por cima). A voz
     *  sintética do Android só assume quando não há ponte configurada ou o
     *  perito desligou o assistente. */
    fun falarSeSemIa(texto: String) {
        val iaExiste = BuildConfig.PV_PONTE_URL.isNotBlank() && !iaDesligadaManual
        if (!iaExiste) vozFeedback.falar(texto)
    }

    // ── Comando de voz ────────────────────────────────────────────────────
    // Declarado ANTES do coletor de eventos porque ele precisa acionar a voz
    // (uma funcao/variavel local so e visivel abaixo de onde foi declarada).
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

    // ── Backend: endereco editavel, cliente e sessao aberta ────────────────
    val escopo = rememberCoroutineScope()
    var enderecoBackend by remember { mutableStateOf(BACKEND_PADRAO) }
    val backend = remember { BackendClient(BACKEND_PADRAO) }
    var sessaoId by remember { mutableStateOf<String?>(null) }
    var laudoId by remember { mutableStateOf<String?>(null) }
    var ocupado by remember { mutableStateOf(false) }
    var fotosEnviadas by remember { mutableIntStateOf(0) }
    /** Capturas que NÃO viraram foto: os óculos recusaram e ficou só a marca no
     *  vídeo, para o servidor tentar recortar o quadro no fim. Contado separado
     *  de propósito — misturar as duas coisas escondeu do perito que o laudo
     *  ia sair sem imagem (campo 08/09/2026). */
    var quadrosMarcados by remember { mutableIntStateOf(0) }
    /** Nome (ou matrícula) de quem o SERVIDOR pôs como responsável pela
     *  perícia — o que decide em qual lista o laudo aparece no painel. */
    var peritoDaSessao by remember { mutableStateOf<String?>(null) }
    /** FOTOS JÁ RECEBIDAS pelo servidor, com a miniatura baixada — o perito
     *  confere na bancada se a foto saiu boa (foco, luz, enquadramento) antes
     *  de sair da mesa. Pedido de campo 08/09/2026: "às vezes as fotos ficaram
     *  ruins, aí precisamos ver como ficou". O JPEG nunca passa pelo app na
     *  captura (os óculos sobem direto ao servidor), então vem de volta daqui. */
    var fotosDaPericia by remember { mutableStateOf<List<FotoNaTela>>(emptyList()) }
    var fotoAmpliada by remember { mutableStateOf<FotoNaTela?>(null) }
    /** A mesma foto em resolução alta, baixada quando o perito amplia. A
     *  miniatura de 480 px esticada na tela do tablet é upscale de 3x: dá para
     *  ver enquadramento, mas NÃO dá para julgar foco — que é justamente por
     *  que o perito abre a foto. */
    var bitmapAmpliado by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(fotoAmpliada?.id) {
        bitmapAmpliado = null
        val f = fotoAmpliada ?: return@LaunchedEffect
        bitmapAmpliado = withContext(Dispatchers.IO) {
            runCatching { miniaturaDe(backend.baixarFoto(f.id), alvoPx = 1600) }.getOrNull()
        }
    }

    // Declaradas aqui (e não junto dos estados da IA, mais acima) porque usam
    // sessaoId — em Kotlin, função local não enxerga variável declarada abaixo.
    fun ligarAssistenteIa() {
        if (ponteGemini != null) return
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
                // Sessão Gemini reabriu (reconexão, troca de trilha, redeploy)
                // e o servidor voltou para conversa. A gravação continua
                // pausada: reafirma para a ponte e não religa nada.
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
        // Triagem: a IA vai perguntar "objeto cortante ou peça íntima?".
        ponte.onTriagem = { iaPerguntandoTrilha = true; iaTrilha = null }
        ponte.onVoz = { d -> iaVoz = d }
        // Trilha definida: a sessão de trabalho está de pé com o roteiro certo.
        // Fica no cartão e vai para a trilha de auditoria da sessão (evento
        // 'sistema'), para o laudo e o histórico saberem qual roteiro guiou.
        ponte.onTrilha = { id, nome, origem ->
            iaPerguntandoTrilha = false
            iaTrilha = if (id == "nenhuma") nome else "Trilha ${id.uppercase()} — $nome"
            status = when (origem) {
                "perito" -> "Roteiro definido pelo perito: $nome."
                "memoria" -> "Sessão retomada — roteiro mantido: $nome."
                else -> "Roteiro fixado em Configurações: $nome."
            }
            sessaoId?.let { sid ->
                escopo.launch { backend.registrarEvento(sid, "sistema", "trilha:$id", "ponte:$origem") }
            }
        }
        // As transcrições chegam em PEDAÇOS (streaming): acumula em vez de
        // substituir — antes cada pedacinho apagava o anterior e o texto
        // "passava correndo" na tela. Fala nova do perito (primeiro pedaço
        // depois de uma resposta) limpa o par e começa o turno seguinte.
        ponte.onVideoAtivo = { iaEnxergando = true }
        ponte.onVisao = { ativa -> iaOlhandoAgora = ativa }
        ponte.onTranscricao = { t ->
            // TUDO que o perito fala vira narração do laudo (buffer com
            // descarga por pausa — ver LaunchedEffect da narração).
            narracaoPendente += t
            narracaoUltimoMs = System.currentTimeMillis()
            // Na TELA, só as falas dirigidas à IA (contêm o chamado). A fala
            // comum do perito não é conversa com o app — poluía o cartão.
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
    // NARRAÇÃO → LAUDO: as transcrições chegam em pedacinhos; junta e, após
    // 2 s sem fala nova (ou 1500+ caracteres acumulados), grava o trecho no
    // backend (pericia.trecho_narracao). É esta narração que o gerador usa
    // como fonte primária para preencher os campos do laudo.
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
    // AUTOMÁTICO: sessão abriu → assistente liga sozinho; sessão fechou →
    // desliga junto. Se o perito desligar à mão no meio da sessão, fica
    // desligado até a próxima sessão (nada religa antes disso) — o toque
    // dele manda mais que o automatismo.
    LaunchedEffect(sessaoId) {
        if (sessaoId != null) {
            iaDesligadaManual = false
            ligarAssistenteIa()
        } else {
            ponteGemini?.encerrar()
            ponteGemini = null
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
    // Brilho mínimo enquanto a tela está "apagada" e a tela não pode dormir
    // (se o Android bloqueasse, o app iria para o fundo). Restaura ao voltar.
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
    // Rede do local vem salva (Configurações) e vai sozinha aos óculos ao conectar.
    var wifiSsid by remember { mutableStateOf(config.wifiSsid) }
    var wifiSenha by remember { mutableStateOf(config.wifiSenha) }
    // Matrícula: a última que abriu perícia neste tablet (Configurações), ou a
    // de dev do local.properties na primeira vez. Protocolo: sempre vazio.
    var matricula by remember { mutableStateOf(config.ultimaMatricula.ifBlank { MATRICULA_PADRAO }) }
    // Credencial DO TABLET (local.properties), não do perito: some da tela.
    val senhaPerito = SENHA_PADRAO
    var protocolo by remember { mutableStateOf("") }
    var rtmpUrl by remember { mutableStateOf<String?>(null) }
    var videoLigado by remember { mutableStateOf(false) }

    // ── RECEPTOR LOCAL (05/09/2026): o tablet como destino do vídeo ──────────
    // Com config.videoNoTablet, os óculos publicam para este tablet na Wi-Fi da
    // bancada em vez da VPS. O receptor grava os .flv em filesDir/video/<sessão>
    // e sobe cada segmento ao servidor quando ele fecha (pausa, queda, fim).
    // Congelado por sessão: trocar em Configurações com a perícia aberta não
    // pode religar o stream no meio — a tela promete "vale para a próxima".
    val videoNoTablet = remember(sessaoId) { config.videoNoTablet }
    val receptor = remember { com.example.peritavision.rtmp.ReceptorDeVideo(context) }
    // ── A FOTO PELO TABLET ───────────────────────────────────────────────────
    // O vídeo já vinha para cá pela Wi-Fi da bancada; a foto ia sozinha para o
    // webhook público, na internet. Em 08/09/2026 o vídeo chegou inteiro e as
    // duas fotos se perderam no caminho. Agora as duas coisas entram pela mesma
    // porta: os óculos entregam o JPEG ao tablet, o tablet mostra a miniatura
    // na hora e repassa ao servidor com repetição.
    // A IMAGEM dos óculos na tela do tablet. O receptor já entregava os
    // quadros; ninguém decodificava, e o cartão mostrava só números — o perito
    // olhou o retângulo preto e disse "o vídeo não aparece no tablet".
    val decodificador = remember { com.example.peritavision.rtmp.DecodificadorDeVideo() }
    var decodificadorEstado by remember {
        mutableStateOf(com.example.peritavision.rtmp.DecodificadorDeVideo.Estado())
    }
    val receptorFotos = remember { com.example.peritavision.net.ReceptorDeFotos(context) }
    /** Autorizações em voo, por requestId: o tablet precisa do token de uso
     *  único para repassar o JPEG ao backend. */
    val credenciaisDeCaptura = remember {
        java.util.concurrent.ConcurrentHashMap<String, com.example.peritavision.net.BackendClient.CredencialCaptura>()
    }
    /** Fotos já no tablet, esperando o servidor aceitar. */
    var fotosAguardandoRede by remember { mutableIntStateOf(0) }
    /** Capturas que o relógio já contou como MARCA no vídeo. Se a foto chegar
     *  depois — e chega, quando a Wi-Fi engasga —, a marca é desfeita. */
    val marcadosSemFoto = remember { java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }
    /** Um repasse por captura de cada vez: o `aoReceber` e a varredura de
     *  pendentes podem olhar o mesmo arquivo ao mesmo tempo. */
    val enviosEmCurso = remember { java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }

    /** Fotos completas paradas no tablet, esperando o servidor. Contado a
     *  partir da PASTA, não de um contador na memória: o perito precisa saber
     *  que sobrou foto mesmo depois de o app ser reaberto. */
    var fotosParadasNoTablet by remember { mutableIntStateOf(0) }
    /** Matrícula digitada que o servidor não reconheceu: a perícia ficou no
     *  nome de outro perito, e isso tem de ser DITO na abertura. */
    var matriculaNaoCadastrada by remember { mutableStateOf<String?>(null) }
    /** Diagnóstico do receptor de fotos, para a bancada saber se os óculos
     *  chegaram na porta — sem depender de logcat. */
    var receptorFotosEstado by remember {
        mutableStateOf(com.example.peritavision.net.ReceptorDeFotos.Estado())
    }
    /** Os óculos não chegaram NEM na porta do tablet: as próximas fotos voltam
     *  a ser endereçadas ao webhook público, que é o caminho que já funcionava.
     *  Uma tentativa sem NENHUMA conexão TCP é prova suficiente — insistir só
     *  perderia mais fotos. */
    var tabletNaoRecebeFoto by remember { mutableStateOf(false) }

    /** Grava a credencial ao lado do JPEG. Em memória ela morria com o
     *  processo e a foto ficava órfã, sem ninguém que pudesse repassá-la. */
    fun guardarCredencial(sessao: String, c: BackendClient.CredencialCaptura) {
        credenciaisDeCaptura[c.requestId] = c
        runCatching {
            receptorFotos.pasta.mkdirs()
            receptorFotos.arquivoDeCredencial(c.requestId).writeText(
                org.json.JSONObject()
                    .put("sessaoId", sessao)
                    .put("requestId", c.requestId)
                    .put("webhookUrl", c.webhookUrl)
                    .put("authToken", c.authToken)
                    .toString()
            )
        }
    }

    /** Credencial de uma captura: da memória, senão do arquivo ao lado do JPEG.
     *  `daSessao` garante que a foto da perícia anterior não seja contada
     *  como foto desta. */
    fun credencialDe(requestId: String, daSessao: String?): BackendClient.CredencialCaptura? {
        credenciaisDeCaptura[requestId]?.let { return it }
        val arquivo = receptorFotos.arquivoDeCredencial(requestId)
        if (!arquivo.exists()) return null
        return runCatching {
            val j = org.json.JSONObject(arquivo.readText())
            if (daSessao != null && j.optString("sessaoId") != daSessao) return null
            BackendClient.CredencialCaptura(
                j.optString("requestId"), j.optString("webhookUrl"), j.optString("authToken"),
            )
        }.getOrNull()
    }

    /**
     * Repassa ao backend uma foto que já está no tablet, insistindo. Devolve
     * true quando o servidor aceitou (e só então o arquivo sai de pendentes).
     */
    suspend fun repassarFoto(requestId: String, arquivo: java.io.File, daSessao: String?, tentativas: Int): Boolean {
        val cred = credencialDe(requestId, daSessao) ?: return false
        if (!enviosEmCurso.add(requestId)) return false
        try {
            for (t in 1..tentativas) {
                try {
                    withContext(Dispatchers.IO) { backend.enviarFoto(cred, arquivo) }
                    withContext(Dispatchers.IO) {
                        if (!receptorFotos.marcarEnviada(arquivo)) {
                            android.util.Log.w("PV-Fotos", "não consegui marcar ${arquivo.name} como enviada")
                        }
                        receptorFotos.arquivoDeCredencial(requestId).delete()
                    }
                    credenciaisDeCaptura.remove(requestId)
                    return true
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Cancelamento NÃO é falha de rede: se engolido aqui, o
                    // arquivo ficava pendente com o servidor já tendo a
                    // imagem, e a sessão seguinte a subia de novo.
                    throw e
                } catch (e: Exception) {
                    val codigo = (e as? com.example.peritavision.net.BackendException)?.codigo ?: 0
                    // Token de USO ÚNICO: 401/403/409/410 depois de uma
                    // tentativa que talvez tenha chegado significa que o
                    // servidor JÁ tem esta foto. Insistir era prometer
                    // "tento de novo" para sempre, com a foto já no laudo.
                    if (t > 1 && codigo in listOf(401, 403, 409, 410)) {
                        android.util.Log.i("PV-Fotos", "repasse de $requestId: token queimado ($codigo) — foto já estava no servidor")
                        withContext(Dispatchers.IO) {
                            receptorFotos.marcarEnviada(arquivo)
                            receptorFotos.arquivoDeCredencial(requestId).delete()
                        }
                        credenciaisDeCaptura.remove(requestId)
                        return true
                    }
                    android.util.Log.w("PV-Fotos", "repasse de $requestId, tentativa $t: ${e.message}")
                    if (t < tentativas) kotlinx.coroutines.delay(3_000L * t)
                }
            }
            return false
        } finally {
            enviosEmCurso.remove(requestId)
            fotosParadasNoTablet = runCatching { receptorFotos.pendentes().size }.getOrDefault(0)
        }
    }
    var receptorEstado by remember { mutableStateOf(com.example.peritavision.rtmp.ReceptorDeVideo.Estado()) }
    var segmentosSubindo by remember { mutableIntStateOf(0) }
    var segmentosSubidos by remember { mutableIntStateOf(0) }
    var segmentosComFalha by remember { mutableIntStateOf(0) }
    LaunchedEffect(receptor) {
        receptor.aoMudar = { e -> receptorEstado = e }
        receptor.aoQuadro = { q -> decodificador.aceitar(q) }
        decodificador.aoMudar = { e -> escopo.launch { decodificadorEstado = e } }
        receptor.aoSegmentoFechado = { chave, arquivo ->
            segmentosSubindo += 1
            escopo.launch(kotlinx.coroutines.Dispatchers.IO) {
                val inicioMs = arquivo.name.removeSuffix(".flv").toLongOrNull() ?: arquivo.lastModified()
                var ok = false
                for (tentativa in 1..3) {
                    try {
                        val shaServidor = backend.enviarSegmentoVideo(chave, arquivo, inicioMs)
                        val shaLocal = com.example.peritavision.domain.Hashing.sha256(arquivo)
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
    // Receptor de FOTOS: ligado sempre que há sessão e o tablet tem IP na
    // Wi-Fi — inclusive quando o vídeo vai direto ao servidor, porque o
    // problema da foto nunca foi o vídeo, foi o alcance da internet.
    LaunchedEffect(sessaoId) {
        if (sessaoId != null) {
            val ip = withContext(Dispatchers.IO) { receptor.ipNaWifi() }
            val erro = if (ip == null) "tablet sem IP na Wi-Fi" else withContext(Dispatchers.IO) { receptorFotos.ligar(ip) }
            if (erro != null) {
                status = "As fotos vão direto ao servidor ($erro) — se a bancada não tiver internet, elas não chegam"
            }
            fotosParadasNoTablet = withContext(Dispatchers.IO) {
                runCatching { receptorFotos.pendentes().size }.getOrDefault(0)
            }
        } else {
            withContext(Dispatchers.IO) { receptorFotos.desligar() }
        }
    }
    // Chegou um JPEG dos óculos: sela, repassa ao backend e só então conta.
    LaunchedEffect(receptorFotos) {
        receptorFotos.aoMudar = { e -> escopo.launch { receptorFotosEstado = e } }
        receptorFotos.aoEstranhar = { texto ->
            escopo.launch { status = "Envio dos óculos não virou foto: $texto" }
        }
        // Só aceita depositar foto quem tem uma captura em aberto (o requestId
        // é um uuid do servidor). Se os óculos mandarem o Bearer, ele também é
        // conferido. Sem isto qualquer aparelho da rede poderia sobrescrever a
        // foto do perito e queimar o token de uso único.
        receptorFotos.autorizacaoValida = { requestId, token ->
            val cred = credenciaisDeCaptura[requestId]
            cred != null && (token == null || cred.authToken.isBlank() || token == cred.authToken)
        }
        receptorFotos.aoReceber = { r ->
            escopo.launch {
                val daSessao = sessaoId
                if (credencialDe(r.requestId, daSessao) == null) {
                    android.util.Log.w("PV-Fotos", "foto ${r.requestId} sem credencial: repasse impossível")
                    status = "Foto chegou ao tablet sem autorização casada (${r.requestId.take(8)}) — ficou no tablet"
                    return@launch
                }
                // MINIATURA NA HORA, do arquivo local: é o motivo de a foto vir
                // pelo tablet. O perito julga foco e luz sem depender de a
                // imagem ir ao servidor e voltar.
                val local = withContext(Dispatchers.IO) {
                    runCatching { miniaturaDe(r.arquivo.readBytes()) }.getOrNull()
                }
                if (local != null) {
                    fotosDaPericia = fotosDaPericia + FotoNaTela(
                        id = "tablet:${r.requestId}", miniatura = local, doVideo = false,
                        kb = r.bytes / 1024, requestId = r.requestId,
                    )
                }
                fotosParadasNoTablet = withContext(Dispatchers.IO) {
                    runCatching { receptorFotos.pendentes().size }.getOrDefault(0)
                }
                fotosAguardandoRede += 1
                val ok = try { repassarFoto(r.requestId, r.arquivo, daSessao, 4) } finally { fotosAguardandoRede -= 1 }
                if (ok) {
                    fotosEnviadas += 1
                    // Chegou depois de o relógio já ter marcado o quadro: a
                    // marca deixa de existir, porque agora há foto de verdade.
                    if (marcadosSemFoto.remove(r.requestId)) {
                        quadrosMarcados = (quadrosMarcados - 1).coerceAtLeast(0)
                    }
                    tabletNaoRecebeFoto = false
                    status = "Foto $fotosEnviadas no servidor ✓ (${r.bytes / 1024} kB)"
                    falarSeSemIa("Foto capturada. Descreva a evidência.")
                } else {
                    status = "Foto ESTÁ NO TABLET (${r.bytes / 1024} kB) e o servidor ainda não aceitou — " +
                        "tento a cada 30 s e antes de finalizar"
                }
            }
        }
    }
    // VARREDURA DAS PENDENTES. A promessa de "tento de novo" era falsa: nada
    // relia a pasta. Agora, enquanto a sessão está aberta, cada foto parada no
    // tablet é oferecida ao servidor de meio em meio minuto.
    LaunchedEffect(sessaoId) {
        val daSessao = sessaoId ?: return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(30_000)
            val paradas = withContext(Dispatchers.IO) { receptorFotos.pendentes() }
            fotosParadasNoTablet = paradas.size
            for (arquivo in paradas) {
                val id = receptorFotos.requestIdDoArquivo(arquivo)
                if (credencialDe(id, daSessao) == null) continue
                fotosAguardandoRede += 1
                val ok = try { repassarFoto(id, arquivo, daSessao, 1) } finally { fotosAguardandoRede -= 1 }
                if (ok) {
                    fotosEnviadas += 1
                    if (marcadosSemFoto.remove(id)) {
                        quadrosMarcados = (quadrosMarcados - 1).coerceAtLeast(0)
                    }
                    status = "Foto atrasada subiu ao servidor ✓ (${arquivo.length() / 1024} kB)"
                }
            }
        }
    }
    // Liga o receptor quando a sessão abre no modo tablet; desliga quando fecha.
    LaunchedEffect(sessaoId, videoNoTablet) {
        if (sessaoId != null && videoNoTablet) {
            (device as? MentraGlassesDevice)?.perfilVideo =
                if (config.qualidadeVideoTablet == ConfiguracoesApp.QUALIDADE_1080P30) MentraGlassesDevice.PerfilVideo.TABLET_1080P30
                else MentraGlassesDevice.PerfilVideo.TABLET_720P30
            // Socket fora da thread principal (o Android barra bind/close na main).
            val erro = withContext(Dispatchers.IO) { receptor.ligar() }
            status = if (erro == null) "Receptor de vídeo do tablet ligado em ${receptor.estado.ip}:${receptor.estado.porta}"
                     else "Receptor de vídeo: $erro"
        } else {
            (device as? MentraGlassesDevice)?.perfilVideo = MentraGlassesDevice.PerfilVideo.SERVIDOR
            if (sessaoId == null) withContext(Dispatchers.IO) { receptor.desligar() }
        }
    }
    /** URL que vai para os óculos: o tablet (modo teste) ou a VPS (como sempre). */
    // Lê receptorEstado (snapshot state) de propósito: é ele que faz a
    // composição recalcular a URL quando o receptor termina de subir.
    val urlDoStream: String? =
        if (videoNoTablet) sessaoId?.takeIf { receptorEstado.ligado }?.let { receptor.urlPara(it) } else rtmpUrl

    // "Visão dos óculos": o mesmo stream RTMP que os óculos mandam ao backend,
    // devolvido como HTTP-FLV pelo node-media-server. Porta 8100 porque no
    // servidor de produção a 8001 já estava ocupada por outro serviço —
    // RTMP_HTTP_PORT=8100 no Coolify tem que estar igual a esta constante.
    // rtmp://host:1935/pv/ID → http://host:8100/pv/ID.flv
    // No modo tablet não existe HTTP-FLV: o cartão mostra o que o receptor recebe.
    val urlVisao = if (videoNoTablet) null else rtmpUrl?.let { r ->
        Regex("^rtmp://([^:/]+)(?::\\d+)?/(.+)$").find(r)?.let { m ->
            "http://${m.groupValues[1]}:8100/${m.groupValues[2]}.flv"
        }
    }

    // ── Leitura de lacre PELOS ÓCULOS ─────────────────────────────────────
    // O perito toca no botão, os óculos fotografam o código de barras, o
    // servidor decodifica e consulta o Atena; a ficha do caso aparece na tela.
    var fichaLacre by remember { mutableStateOf<BackendClient.FichaLacre?>(null) }
    var lendoLacre by remember { mutableStateOf(false) }
    /** O que o Atena devolveu ao abrir a sessão — vira contexto do assistente IA. */
    var casoAtena by remember { mutableStateOf<BackendClient.CasoAtena?>(null) }
    LaunchedEffect(sessaoId) { if (sessaoId == null) casoAtena = null }
    // Sessão nova → a lista de narrações da tela começa do zero.
    LaunchedEffect(sessaoId) { if (sessaoId != null) narracoes = emptyList() }
    /** Texto da requisição do Atena — o que a autoridade pediu. */
    var textoRequisicao by remember { mutableStateOf<String?>(null) }
    /** Por que NÃO há texto: sem documento no caso, ou documento ilegível. O
     *  contexto da IA precisa disso — faltar o texto e faltar a informação de
     *  que não existe requisição são coisas diferentes para quem conduz. */
    var avisoRequisicao by remember { mutableStateOf<String?>(null) }
    /** true enquanto o documento está sendo baixado. O contexto NÃO sai nesse
     *  meio: sem isto o assistente abria a perícia anunciando "não há
     *  requisição anexada" e, quando o texto chegava, já não fazia o resumo
     *  de abertura (o prompt só o faz na primeira vez). */
    var requisicaoCarregando by remember { mutableStateOf(false) }
    LaunchedEffect(casoAtena) {
        textoRequisicao = null
        avisoRequisicao = null
        requisicaoCarregando = false
        val caso = casoAtena ?: return@LaunchedEffect
        val docId = caso.documentoId
        if (docId.isNullOrBlank()) {
            avisoRequisicao = "o caso não tem documento anexado no Atena"
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

    // ── O assistente IA "vê" e "conhece o caso" ─────────────────────────────
    // Sempre que a ponte (re)abrir ou o vídeo/ficha mudarem, manda ao servidor
    // da IA: a URL do FLV dos óculos (o servidor puxa ~1 quadro/s dela e o
    // Gemini passa a ver a bancada) e a ficha do lacre como contexto textual
    // (para responder "qual o nome do solicitante?" sem inventar).
    LaunchedEffect(ponteGemini, urlVisao) {
        ponteGemini?.definirVideo(urlVisao)
        // Cinto de segurança: enquanto o assistente não confirmar que está
        // ENXERGANDO, reenvia a URL do vídeo a cada 20 s. Cobre mensagem
        // perdida na reconexão e corrida entre a sessão Gemini abrir e o
        // pedido de vídeo chegar.
        while (ponteGemini != null && urlVisao != null && !iaEnxergando) {
            kotlinx.coroutines.delay(20_000)
            if (!iaEnxergando) ponteGemini?.definirVideo(urlVisao)
        }
    }
    LaunchedEffect(
        ponteGemini, fichaLacre, casoAtena, textoRequisicao, avisoRequisicao,
        requisicaoCarregando, protocolo,
        // Sem estas duas chaves o contexto podia ser montado ANTES de a sessão
        // dizer de quem é a perícia, e o aviso de responsabilidade nunca
        // chegaria à IA — que é justamente o que ele precisa anunciar.
        peritoDaSessao, matriculaNaoCadastrada,
    ) {
        if (ponteGemini == null) return@LaunchedEffect
        // Espera o documento decidir se existe ou não antes de abrir a boca.
        if (requisicaoCarregando) return@LaunchedEffect
        val f = fichaLacre
        val a = casoAtena
        val texto = buildString {
            // Primeira vez → resumo de abertura falado (único momento em que a
            // IA fala sem ser chamada); reenvio (reconexão) → só "Contexto
            // atualizado." — o modo de chamada por nome está no prompt (1.1).
            append("CONTEXTO DO CASO (na PRIMEIRA vez, faça o resumo de abertura falado; se já o fez nesta sessão, responda apenas \"Contexto atualizado.\"): ")
            append("protocolo ${protocolo.trim().ifBlank { "não informado" }}.")
            // AVISO DE RESPONSABILIDADE (08/09/2026): a matrícula digitada não
            // existe no cadastro, então o laudo vai sair no nome de outra
            // pessoa. O perito de luvas não lê a tela — quem tem de dizer isso
            // é a voz, na primeira frase da abertura. O núcleo do prompt tem a
            // regra que obriga isso.
            matriculaNaoCadastrada?.let { mat ->
                append(" AVISO DE RESPONSABILIDADE: a matrícula $mat, digitada no tablet, NÃO está")
                append(" cadastrada no sistema. Esta perícia está registrada no nome de")
                append(" ${peritoDaSessao ?: "quem está logado no tablet"}, e é essa pessoa que vai")
                append(" assinar o laudo.")
            }
            peritoDaSessao?.takeIf { matriculaNaoCadastrada == null }?.let {
                append(" Perito responsável por esta perícia: $it.")
            }
            // Dados do Atena resolvidos na abertura da sessão — cobrem o caso
            // aberto por protocolo digitado, sem passar pela leitura do lacre.
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
                // RECORTE, não o texto integral. Desde que o backend passou a
                // ler o ZIP da requisição inteira, isto podia ter 120 mil
                // caracteres: um turno de texto desse tamanho na sessão Live
                // come a janela de contexto e a IA emudece. O laudo usa o
                // texto completo; a bancada precisa do começo, que é onde
                // está o histórico do fato e o que a autoridade pediu.
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
            } else {
                // Sem isto a IA só ficava sem o texto e improvisava. Agora ela
                // sabe que não há requisição e o que dizer ao perito.
                append(" SEM REQUISIÇÃO NESTA SESSÃO: ${avisoRequisicao ?: "documento do Atena indisponível"}. ")
                append("Você NÃO tem o que a autoridade pediu. Conduza pelo roteiro e pelos dados de cadastro acima. ")
                append("Se o perito perguntar o que foi requisitado, diga exatamente que não há requisição legível ")
                append("anexada a este protocolo e ofereça registrar o que ele ditar. NUNCA invente, resuma nem ")
                append("suponha o teor da requisição, do histórico do fato ou do boletim de ocorrência.")
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

    /** Login -> resolve o protocolo -> abre a sessao. Um botao so. */
    fun iniciarSessao() {
        if (ocupado) return
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
                val aberta = backend.abrirSessao(caso.id, perfilId, matricula.trim())
                config.ultimaMatricula = matricula // lembra para a próxima perícia
                sessaoId = aberta.sessaoId
                rtmpUrl = aberta.rtmpUrl
                laudoId = null
                if (aberta.retomada) {
                    // O backend achou a sessão que ficou aberta (app caiu, tablet
                    // reiniciou): a perícia continua nela, com as fotos que já
                    // estavam lá — e vai gerar UM laudo só.
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
                // DE QUEM É A PERÍCIA. O tablet é compartilhado e entra no
                // backend com a credencial dele, não com a do perito; quem
                // separa os laudos é a matrícula digitada. Quando ela não
                // existe, a perícia ficava no nome do usuário do tablet EM
                // SILÊNCIO — o perito só descobria não achando o laudo na
                // lista dele (campo 08/09/2026).
                peritoDaSessao = aberta.peritoNome ?: aberta.peritoMatricula
                matriculaNaoCadastrada = aberta.matriculaDesconhecida
                if (aberta.matriculaDesconhecida != null) {
                    status = "ATENÇÃO: matrícula ${aberta.matriculaDesconhecida} não existe no sistema — " +
                        "a perícia ficou no nome de ${peritoDaSessao ?: "quem está logado no tablet"}"
                    vozFeedback.falar(
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

    /**
     * Fecha a sessao; o backend monta o laudo. E DEVOLVE O RESULTADO REAL em
     * `aoTerminar` — quem pediu (a IA) so anuncia encerramento depois disso.
     *
     * Campo 04/09: o perito confirmou o encerramento, a IA disse que estava
     * finalizado e a tela FICOU NA BANCADA. Duas causas, as duas aqui:
     *   1. `if (ocupado) return` engolia o pedido em silencio quando outra
     *      chamada estava em curso — nada acontecia e ninguem sabia;
     *   2. quem chamava respondia ok=true ANTES do backend responder, entao
     *      um erro (401, 500, rede) virava so um texto na barra de status,
     *      que o perito de luvas nunca le.
     * Agora: espera a vez em vez de desistir, e o sucesso/erro volta por voz.
     */
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
        // Recusa não pode voltar o rito para a estaca zero: quem chama zera o
        // pedidoFinalizarMs antes, e a próxima tentativa perguntaria "confirma
        // o encerramento?" de novo, logo depois de o perito ter confirmado.
        pedidoFinalizarMs = System.currentTimeMillis()
        finalizando = true
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
                    // Falha aqui nao pode travar o encerramento: o RTMP cai
                    // sozinho quando a sessao fecha no servidor.
                    runCatching { device.pararVideo() }
                    videoLigado = false
                    kotlinx.coroutines.delay(1500)
                }
                if (videoNoTablet) {
                    // Os óculos fecham a publicação, o receptor fecha o segmento e
                    // o upload começa sozinho. Espera isso acabar (teto 3 min) para
                    // o servidor ter o vídeo quando montar o laudo.
                    kotlinx.coroutines.delay(2_500)
                    var esperaVideo = 0
                    while ((segmentosSubindo > 0 || receptorEstado.publicando) && esperaVideo < 180_000) {
                        val mb = receptor.segmentosDe(id).sumOf { it.length() } / 1_000_000.0
                        status = "Subindo o vídeo do tablet para o servidor (${"%.0f".format(mb)} MB restantes)..."
                        kotlinx.coroutines.delay(500); esperaVideo += 500
                    }
                    if (segmentosComFalha > 0 || receptor.segmentosDe(id).isNotEmpty()) {
                        // Isto não é só o vídeo: as fotos que os óculos não
                        // deram são recortadas DO VÍDEO no servidor. Segmento
                        // que não subiu = quadro que não vira imagem no laudo.
                        vozFeedback.falar("Atenção: parte do vídeo ficou no tablet e não subiu. A perícia será finalizada mesmo assim.")
                        status = "Vídeo: ${receptor.segmentosDe(id).size} segmento(s) ficaram no tablet (${receptor.pasta}/$id)"
                        kotlinx.coroutines.delay(2_000)
                    }
                }
                // FOTOS PARADAS NO TABLET. O laço do vídeo acima já existia;
                // as fotos não tinham nenhum. Finalizar cancelava a varredura e
                // derrubava o receptor no mesmo instante em que o laudo era
                // montado — a foto ficava no tablet e o laudo saía sem imagem,
                // exatamente o desfecho de 08/09/2026.
                run {
                    var esperaFoto = 0
                    while (esperaFoto < 120_000) {
                        val paradas = withContext(Dispatchers.IO) { receptorFotos.pendentes() }
                            .filter { credencialDe(receptorFotos.requestIdDoArquivo(it), id) != null }
                        fotosParadasNoTablet = paradas.size
                        if (paradas.isEmpty()) break
                        status = "Subindo ${paradas.size} foto(s) que estavam no tablet..."
                        for (arquivo in paradas) {
                            val rid = receptorFotos.requestIdDoArquivo(arquivo)
                            if (repassarFoto(rid, arquivo, id, 2)) {
                                fotosEnviadas += 1
                                if (marcadosSemFoto.remove(rid)) {
                                    quadrosMarcados = (quadrosMarcados - 1).coerceAtLeast(0)
                                }
                            }
                        }
                        kotlinx.coroutines.delay(2_000); esperaFoto += 2_000
                    }
                    val sobraram = withContext(Dispatchers.IO) { receptorFotos.pendentes() }
                        .count { credencialDe(receptorFotos.requestIdDoArquivo(it), id) != null }
                    fotosParadasNoTablet = sobraram
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
                // Limpa ANTES de mandar: o laço de narração acorda a cada
                // 700 ms e gravava o mesmo trecho outra vez enquanto o POST
                // estava em voo — a frase saía duplicada nas considerações.
                val ultimaFala = narracaoPendente.trim()
                narracaoPendente = ""
                if (ultimaFala.isNotBlank()) {
                    narracoes = narracoes + ultimaFala
                    runCatching { backend.narrar(id, ultimaFala) }
                }
                laudoId = backend.finalizarSessao(id)
                // Confirma para quem pediu ANTES de fechar a ponte (fechar a
                // sessao derruba o WebSocket da IA no efeito de sessaoId).
                aoTerminar?.invoke(true, "sessão encerrada; o laudo entrou em processamento")
                status = "Laudo gerado — revise e baixe no site do PeritaVision"
                falarSeSemIa("Sessão finalizada. Laudo em processamento.")
                // Espera a IA TERMINAR de anunciar antes de fechar a ponte:
                // com 2,5 s fixos o encerrar() cortava a frase no meio ("sessão
                // encerra—") e o perito de luvas não sabia se tinha encerrado.
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
                credenciaisDeCaptura.clear()
                marcadosSemFoto.clear()
                matriculaNaoCadastrada = null
                pedidoFinalizarMs = 0L
                bipe("conversa")
            } catch (e: Exception) {
                // A pericia CONTINUA ABERTA. Libera a retentativa imediata
                // (sem o rito de dois tempos de novo) e avisa por voz.
                pedidoFinalizarMs = System.currentTimeMillis()
                val motivo = e.message ?: "erro desconhecido"
                status = "Erro ao finalizar: $motivo"
                // vozFeedback direto, NÃO falarSeSemIa: com a IA ligada o
                // falarSeSemIa cala, e o perito ficava sem nenhum retorno de
                // um encerramento que falhou (só um texto na barra de status).
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

    // GALERIA DA BANCADA. Recarrega quando o contador de fotos muda (captura
    // nova ou quadro recuperado) e insiste por cerca de três minutos — a foto
    // dos óculos leva alguns segundos para subir pela Wi-Fi deles, e sem
    // insistir a miniatura da última foto não aparecia. Cada captura nova
    // relança o efeito, então na prática a galeria acompanha a perícia toda.
    LaunchedEffect(sessaoId, fotosEnviadas, quadrosMarcados) {
        val id = sessaoId
        if (id == null) { fotosDaPericia = emptyList(); return@LaunchedEffect }
        var tentativas = 0
        while (tentativas < 12) {
            val lista = runCatching { backend.listarFotos(id) }.getOrNull()
            if (lista != null) {
                // Uma por vez, publicando na tela conforme chega: com dez fotos e
                // rede ruim, baixar o lote inteiro antes de mostrar deixava a
                // bancada em branco por minutos.
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
                    // Tira a versão LOCAL da mesma captura: ela já foi mostrada
                    // na bancada assim que o tablet recebeu, e agora a do
                    // servidor (a que vai ao laudo) toma o lugar.
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

    // Injeta no Mentra COMO pedir autorizacao de captura ao backend. Sem isso,
    // falar "capturar" nao faz nada (era exatamente o sintoma anterior).
    LaunchedEffect(device) {
        (device as? MentraGlassesDevice)?.obterAutorizacao = {
            sessaoId?.let { id ->
                val c = backend.solicitarCaptura(id)
                // O receptor recorta o requestId da URL para `[A-Za-z0-9_-]`:
                // se o formato mudar no servidor, a URL do tablet passaria a
                // não casar e TODA foto levaria 403. Nesse caso, webhook.
                val idSeguro = Regex("^[A-Za-z0-9_-]{4,80}$").matches(c.requestId)
                val noTablet = if (idSeguro && !tabletNaoRecebeFoto) receptorFotos.urlPara(c.requestId) else null
                if (noTablet == null) {
                    MentraGlassesDevice.AutorizacaoCaptura(c.requestId, c.webhookUrl, c.authToken)
                } else {
                    guardarCredencial(id, c)
                    // Relógio de desistência: se o arquivo não aparecer, a
                    // captura vira MARCA no vídeo e o perito sabe disso na
                    // bancada, não no painel horas depois. Ele SÓ AVISA — a
                    // credencial fica no mapa, porque os óculos ainda podem
                    // entregar a foto (o comando BLE, a foto MEDIUM e o POST
                    // disputam a mesma Wi-Fi do vídeo). Tirar a credencial aqui
                    // fazia a foto atrasada morrer no tablet.
                    escopo.launch {
                        kotlinx.coroutines.delay(60_000)
                        // Preso À SESSÃO: sem isto, capturar e finalizar em menos
                        // de um minuto fazia "marquei o quadro no vídeo" aparecer
                        // depois de "laudo gerado", na tela da perícia seguinte.
                        if (sessaoId == id && credenciaisDeCaptura.containsKey(c.requestId) &&
                            marcadosSemFoto.add(c.requestId)
                        ) {
                            quadrosMarcados += 1
                            // A pista está no contador de conexões: se ninguém
                            // abriu TCP na porta, os óculos não tentaram — não
                            // é formato de envio, é endereço ou rede. Nesse
                            // caso volta ao webhook público, que já funcionava,
                            // em vez de perder as fotos seguintes também.
                            if (receptorFotosEstado.conexoes == 0) {
                                tabletNaoRecebeFoto = true
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
                    // Caiu o BLE/Wi-Fi: o stream morreu com ele. Sem zerar
                    // isto o vídeo NUNCA religava na reconexão (o efeito exige
                    // !videoLigado) e o cartão seguia mostrando "AO VIVO".
                    if (!evento.conectado) videoLigado = false
                    status = if (evento.conectado) "Óculos conectado" else "Óculos desconectado"
                    // WI-FI AUTOMÁTICO: conectou e há rede salva → envia sem
                    // pedir nada. Espera 2,5 s para os óculos reportarem o
                    // estado deles primeiro — se já estiverem nessa rede, não
                    // reenvia (o envio derruba e religa a conexão).
                    if (evento.conectado && wifiSsid.isNotBlank()) {
                        escopo.launch {
                            delay(2_500)
                            if (conectado && !wifiOculos) {
                                status = "Enviando a Wi-Fi salva (${wifiSsid.trim()}) aos óculos..."
                                (device as? MentraGlassesDevice)?.configurarWifi(wifiSsid.trim(), wifiSenha)
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
                    // do celular, sem o perito precisar fazer nada. Com o
                    // assistente IA ligado nao ha o que assumir — quem entende
                    // o pedido do perito e o Gemini, pelo audio dos oculos.
                    if (ponteGemini == null) {
                        usarVozDoCelular = true
                        voz.iniciar()
                        vozAtiva = true
                    }
                }
                is GlassesEvent.CapturaRemota -> {
                    // Os oculos subiram o JPEG direto ao webhook; o backend selou.
                    // So agora, com a confirmacao real (onPhotoResponse), falamos
                    // "foto capturada" — falar antes disso poderia mentir sobre
                    // uma captura que na verdade falhou.
                    if (evento.fotoDeVerdade) {
                        fotosEnviadas++
                        status = "Foto $fotosEnviadas enviada ao backend ✓"
                        // Pede a descrição EM VOZ, pelos óculos (se pareados como
                        // áudio Bluetooth; senão, pelo celular). A próxima fala do
                        // perito vira a legenda desta foto — o backend ancora o
                        // primeiro trecho posterior ao pedido da captura.
                        falarSeSemIa("Foto capturada. Descreva a evidência.")
                    } else {
                        // MARCA no vídeo, não foto. Conta separado e diz a verdade:
                        // esta imagem só existe se o servidor conseguir recortar o
                        // quadro no fim (campo 08/09/2026: o perito achou que tinha
                        // duas fotos e o laudo saiu com zero).
                        quadrosMarcados++
                        status = "Quadro $quadrosMarcados MARCADO no vídeo — não é foto; o servidor tenta recortar no fim"
                        falarSeSemIa("Não consegui a foto. Marquei o quadro no vídeo. Descreva a evidência.")
                    }
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
        // Com o assistente IA ligado, QUEM manda é o Gemini (função
        // capturar_foto). O reconhecedor de palavras soltas fica só como
        // reserva para quando a IA está desligada — dois donos do mesmo
        // comando tiravam foto em dobro e a IA disparava captura ao dizer
        // "fotografe" no meio de uma frase.
        (device as? MentraGlassesDevice)?.onComandoVoz =
            if (ponteGemini != null) null else ({ device.capturarFoto() })
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
    LaunchedEffect(sessaoId, conectado, usarVozDoCelular, ponteGemini) {
        val mentra = device as? MentraGlassesDevice
        // CUIDADO: iniciarComandoDeVoz() é o que LIGA O MICROFONE dos óculos —
        // é dele que sai o PCM que alimenta o assistente IA e a custódia.
        // Desligar a escuta com a IA ativa deixava a IA SURDA (perito falava
        // e nada acontecia). Então: com óculos, o microfone fica ligado sempre
        // que há sessão — quem foi desligado com a IA é só a AÇÃO offline
        // (onComandoVoz = null, ASR ignorado). Já o reconhecedor do CELULAR
        // (Vosk) não alimenta a IA, só dispara comando — esse sim fica de
        // reserva, ligado apenas com a IA desligada.
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

    /**
     * ÁUDIO DOS ÓCULOS → BACKEND → COMANDO.
     * Este é o caminho oficial da arquitetura. Os óculos entregam PCM por
     */
    // ── Funções de bancada pedidas pelo assistente IA ──────────────────────
    // O Gemini NÃO executa nada: ele pede, o app executa (o MESMO código dos
    // comandos de voz offline — mesma captura selada, mesma cadeia de
    // custódia) e devolve o resultado, que o assistente confirma por voz.
    // Fica aqui (e não em ligarAssistenteIa) porque finalizarSessao é
    // declarada acima e função local não enxerga declaração abaixo dela.
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
                        // O comando offline acabou de fotografar a mesma cena
                        // (a frase do perito continha "capturar"/"foto"):
                        // confirma sem duplicar a evidência.
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
                    // DOIS TEMPOS, TRAVADO EM CÓDIGO. A regra de confirmação no
                    // prompt não bastou: em teste real a IA encerrou a perícia
                    // sozinha no meio de uma resposta, e encerrar é irreversível
                    // (fecha a sessão e dispara o laudo). Agora a PRIMEIRA
                    // chamada nunca encerra — só manda ela perguntar. Só a
                    // segunda, dentro de 2 minutos, executa.
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
                        // Responde SO com o resultado real (campo 04/09: a IA
                        // anunciava encerramento e a tela ficava na bancada).
                        finalizarSessao { ok, detalhe ->
                            ponteGemini?.responderComando(id, nome, ok, detalhe)
                        }
                    }
                }
                else -> ponteGemini?.responderComando(id, nome, false, "função desconhecida")
            }
        }
    }

    // Liga o vídeo dos óculos assim que a sessão abre e os óculos estão prontos.
    // iaModo entra nas chaves: em PAUSA o vídeo fica desligado (ver efeito
    // abaixo) e, quando o perito diz a palavra de volta, este efeito roda de
    // novo e religa o stream — o servidor abre outro segmento .flv e a
    // consolidação junta tudo; o trecho da pausa simplesmente não existe.
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

    // PAUSA de verdade (campo 04/09: "pausa" só calava a IA e o vídeo seguia
    // gravando tudo — o arquivo ficava enorme e o que o perito falava no
    // intervalo ia junto). Agora a pausa CORTA o stream dos óculos; o áudio ao
    // backend também para (abaixo, em onPcm). Só a ponte segue ouvindo, para
    // reconhecer a palavra de volta — e ela descarta a transcrição em pausa.
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
                // Com o assistente IA ativo o ASR do backend não é mais usado
                // para comando nenhum (o áudio segue subindo só por custódia),
                // então o alerta dele viraria barulho na tela do perito.
                val avisoDoAsr = msg.startsWith("Serviço de voz indisponível")
                if (!(avisoDoAsr && ponteGemini != null)) status = msg
            }
            onComando = { intencao, ouvido ->
                // Terceiro caminho offline (ASR do backend). Mesma regra: com
                // o assistente ligado, o dono do comando e o Gemini. O audio
                // CONTINUA subindo ao backend (custodia da sessao) — so a acao
                // automatica sobre ele e que fica suspensa.
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
        // Caminho oficial (offline) + cópia opcional para o assistente IA.
        mentra.onPcm = { pcm, _ ->
            // Meia-duplex: enquanto o assistente fala pelo alto-falante dos
            // óculos, o microfone capta a própria voz da IA. Sem este bloqueio,
            // a IA se ouve (fala sem parar) e um "fotografe" dito por ela
            // dispara o comando de captura no Vosk. Silenciamos os DOIS
            // caminhos (offline e IA) durante a fala + cauda de 400 ms.
            val iaFalando = ponteGemini?.estaFalando() == true
            if (!iaFalando) {
                // Em PAUSA nada do que é dito sobe ao servidor (custódia/narração);
                // a ponte continua ouvindo só para pegar a palavra de volta.
                if (!gravacaoPausada) streamer.enviarPcm(pcm)
                ponteGemini?.enviarPcm(pcm)
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
            // Sem isto, girar o tablet deixava o ServerSocket e a thread do
            // receptor presos na porta: a instância nova caía na porta
            // seguinte e, depois de algumas recriações, não achava porta
            // nenhuma — as fotos voltavam ao webhook público em silêncio.
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
        else -> "Abra a perícia no passo 3 — a foto precisa de um token emitido pelo servidor."
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
    // Numeração do checklist: Óculos → Wi-Fi → Perícia (no modo PHONE, sem
    // óculos, são só dois passos: Câmera → Perícia).
    // Desde 02/09/2026 óculos e Wi-Fi moram na aba Configurações (engrenagem):
    // a tela principal tem UM passo, a perícia. O apoio do título diz o que
    // ainda falta lá em Configurações para poder abrir.
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
            onFinalizar = { finalizarSessao { ok, detalhe -> if (!ok) vozFeedback.falar("Não encerrou. $detalhe") } },
        )
    }
    val cartaoCaptura: @Composable () -> Unit = {
        CartaoCaptura(
            destaque = passo == Passo.CAPTURAR,
            podeCapturar = podeCapturar,
            motivoBloqueio = motivoBloqueio,
            fotosEnviadas = fotosEnviadas,
            quadrosMarcados = quadrosMarcados,
            fotosParadasNoTablet = fotosParadasNoTablet,
            rotaDaFoto = when {
                tabletNaoRecebeFoto -> "A foto vai direto ao servidor (o tablet não recebeu)."
                receptorFotosEstado.ligado && receptorFotosEstado.recebidas > 0 ->
                    "A foto chega no tablet (${receptorFotosEstado.recebidas} até agora) e o tablet repassa ao servidor."
                receptorFotosEstado.ligado && receptorFotosEstado.conexoes > 0 ->
                    "Os óculos chegaram na porta do tablet, última resposta: " +
                        (receptorFotosEstado.ultimoResultado ?: "sem resposta")
                receptorFotosEstado.ligado ->
                    "Esperando a foto em http://${receptorFotosEstado.ip}:${receptorFotosEstado.porta} " +
                        "(ninguém chegou à porta ainda)"
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
            imagem = if (videoNoTablet) ({ VisorAoVivoDosOculos(decodificador) }) else null,
            imagemEstado = if (videoNoTablet) decodificadorEstado else null,
        )
    }
    // FOTOS DA PERÍCIA, embaixo do vídeo: miniaturas do que o servidor já tem.
    val cartaoFotos: @Composable () -> Unit = {
        if (temSessao) CartaoFotosDaPericia(
            fotos = fotosDaPericia,
            // Só o que o app contou como FOTO: quadro marcado não está "subindo",
            // ele só existe se o servidor recortar no fim — senão o placeholder
            // ficaria na tela para sempre.
            esperando = (fotosEnviadas - fotosDaPericia.count { !it.doVideo }).coerceAtLeast(0) + fotosAguardandoRede,
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
                // Com a IA desligada os botões continuam valendo: senão o
                // perito que desligou o assistente ficava sem NENHUMA forma
                // de pausar a gravação (nem voz, nem toque).
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
        // background ANTES do inset: a cor do cabeçalho sobe por baixo da barra
        // de status, em vez de deixar uma faixa de outra cor lá em cima.
        Column(
            Modifier
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding()
        ) {
            BarraDeTopo(
                titulo = "PeritaVision",
                // O nome do responsável na barra de topo: o perito confere de
                // relance que a perícia é dele antes de começar a trabalhar.
                subtitulo = if (temSessao) {
                    "Protocolo ${protocolo.trim()} · ${peritoDaSessao ?: "sessão aberta"}"
                } else SLOGAN_APP,
                logo = R.drawable.logo_politec,
                onConfiguracoes = { mostrarConfiguracoes = true },
            )
        }
        FaixaProntidao(prontidao)

        // ── LAYOUT EM COLUNA ÚNICA (redesenho 01/09/2026) ────────────────
        // Sem sessão: PREPARAÇÃO — checklist numerado, um passo por vez
        //   (óculos → Wi-Fi → perícia). Passo concluído recolhe numa linha
        //   verde; passo ainda bloqueado fica esmaecido.
        // Com sessão: BANCADA — visor ao vivo, captura selada, assistente,
        //   custódia. Óculos e Wi-Fi ficam recolhidos no fim, ainda ao alcance.
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
                cartaoEvidencia()
            } else {
                cartaoVisao()
                cartaoFotos()
                cartaoCaptura()
                cartaoLaudo()
                cartaoAssistente()
                cartaoEvidencia()
            }
            RodapeMarca(NOME_EMPRESA)
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
    // Configurações POR CIMA da bancada (ver comentário do Box): a sessão, os
    // efeitos e o assistente continuam vivos enquanto o perito mexe aqui.
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
    if (mostrarConfiguracoes) {
        TelaConfiguracoes(
            config = config,
            urlPonte = BuildConfig.PV_PONTE_URL,
            onVoltar = { mostrarConfiguracoes = false },
            // Óculos e Wi-Fi: os mesmos cartões de antes, com o mesmo estado —
            // só mudaram de tela. Conectar aqui reflete na faixa de prontidão.
            secaoOculos = {
                cartaoOculos()
                cartaoWifi()
            },
        )
    }
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  CARTÕES — cada um é só apresentação: recebe estado, devolve toques.
// ══════════════════════════════════════════════════════════════════════════

@Composable
private fun CartaoOculos(
    numero: Int,
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
        CartaoPasso(
            numero = numero,
            titulo = "Câmera do celular",
            etiqueta = if (temPermissoes) "pronto" else "sem permissão",
            tomEtiqueta = if (temPermissoes) Tom.OK else Tom.ERRO,
            ativo = !temPermissoes,
            bloqueado = false,
            concluido = temPermissoes,
            resumoConcluido = "Modo de teste — a foto é tirada pelo celular",
        ) {
            TextoApoio(
                "TIPO_DISPOSITIVO está em PHONE. A foto é tirada pelo celular e " +
                    "selada localmente — os óculos não participam."
            )
        }
        return
    }

    CartaoPasso(
        numero = numero,
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
        ativo = destaque,
        bloqueado = false,
        concluido = conectado,
        resumoConcluido = "Conectado por Bluetooth",
    ) {
        TextoApoio(
            when {
                conectado -> "O Bluetooth leva os comandos e o áudio do microfone. A foto sobe pela Wi-Fi dos óculos."
                conectando -> "Procurando os óculos por Bluetooth..."
                else -> "Ligue os óculos, deixe-os por perto e toque em conectar. É preciso Bluetooth e Localização ativos."
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
    numero: Int,
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
    CartaoPasso(
        numero = numero,
        titulo = "Wi-Fi do local",
        etiqueta = when {
            wifiOculos -> "conectado"
            conectado -> "pendente"
            else -> "aguardando"
        },
        tomEtiqueta = when {
            wifiOculos -> Tom.OK
            conectado -> Tom.ERRO
            else -> Tom.NEUTRO
        },
        ativo = destaque,
        bloqueado = !conectado,
        concluido = wifiOculos,
        resumoConcluido = "Rede ${ssidOculos ?: "—"}",
    ) {
        TextoApoio(
            if (wifiOculos) {
                "É por esta rede que o JPEG sobe ao servidor."
            } else {
                "As fotos e o vídeo sobem pelo Wi-Fi DOS ÓCULOS — rede 2,4 GHz com internet. " +
                    "A rede fica salva no tablet e é enviada sozinha toda vez que os óculos conectam."
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
            segredo = true,
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
    numero: Int,
    bloqueado: Boolean,
    destaque: Boolean,
    onLerLacre: () -> Unit,
    matricula: String,
    onMatricula: (String) -> Unit,
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

    CartaoPasso(
        numero = numero,
        titulo = "Perícia",
        etiqueta = if (temSessao) "sessão aberta" else "ATENA",
        tomEtiqueta = if (temSessao) Tom.OK else Tom.NEUTRO,
        ativo = destaque,
        bloqueado = bloqueado && !temSessao,
        concluido = temSessao,
        resumoConcluido = "Protocolo ${protocolo.trim()}",
    ) {
        TextoApoio(
            if (temSessao) {
                "Sessão aberta. Finalizar manda o backend montar o laudo."
            } else {
                "Informe o protocolo — os dados da perícia vêm do ATENA."
            }
        )
        if (bloqueado && !temSessao) {
            Spacer(Modifier.height(4.dp))
            TextoApoio("Óculos desconectados — conecte pela engrenagem, ao lado da logo.", Tom.ATENCAO)
        }
        Spacer(Modifier.height(10.dp))
        if (!temSessao) {
            // QUEM ESTÁ NA BANCADA. Não é login — é identificação: o tablet é
            // compartilhado e entra com a credencial dele (local.properties),
            // e esta matrícula diz de quem é a perícia. É ela que separa os
            // laudos por perito no painel. A senha existe onde importa: no
            // painel web, para revisar e validar o laudo.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CampoPv(
                    valor = matricula,
                    onValueChange = onMatricula,
                    rotulo = "Matrícula",
                    habilitado = editavel && !bloqueado,
                    modifier = Modifier.weight(0.7f),
                )
                CampoPv(
                    valor = protocolo,
                    onValueChange = onProtocolo,
                    rotulo = "Protocolo",
                    habilitado = editavel && !bloqueado,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            BotaoPrimario(
                texto = if (ocupado) "Sincronizando com o ATENA..." else "Iniciar perícia",
                icone = R.drawable.ic_pv_play,
                habilitado = !ocupado && !bloqueado && protocolo.isNotBlank(),
                onClick = onIniciar,
            )
            Spacer(Modifier.height(8.dp))
            BotaoContorno(
                texto = "Ler lacre (QR do envelope)",
                icone = R.drawable.ic_pv_camera,
                habilitado = !ocupado && !bloqueado,
                onClick = onLerLacre,
            )
        } else {
            BotaoContorno(
                texto = if (ocupado) "Finalizando..." else "Finalizar sessão e gerar laudo",
                icone = R.drawable.ic_pv_custodia,
                habilitado = !ocupado,
                tom = Tom.ERRO,
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
    /** Capturas que ficaram só como marca no vídeo — ver quadrosMarcados. */
    quadrosMarcados: Int = 0,
    /** Fotos completas paradas no tablet, esperando o servidor aceitar. */
    fotosParadasNoTablet: Int = 0,
    /** Por onde a foto está indo, em uma frase — o diagnóstico da bancada. */
    rotaDaFoto: String? = null,
    vozAtiva: Boolean,
    ouvindoPelosOculos: Boolean,
    /** true quando o assistente IA está ligado: é ELE quem recebe os pedidos
     *  do perito ("registra uma foto disso", "pode encerrar"), então some da
     *  tela o reconhecedor de palavras soltas, que só existe como reserva. */
    assistenteIa: Boolean,
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
            grande = true,
        )
        if (rotaDaFoto != null) {
            TextoApoio(rotaDaFoto, if (rotaDaFoto.startsWith("A foto chega")) Tom.OK else Tom.NEUTRO)
            Spacer(Modifier.height(6.dp))
        }
        if (motivoBloqueio != null) {
            TextoApoio(motivoBloqueio)
        } else if (fotosEnviadas > 0 || quadrosMarcados > 0 || fotosParadasNoTablet > 0) {
            if (fotosEnviadas > 0) Contador(fotosEnviadas, "fotos enviadas\ne seladas por hash")
            if (fotosParadasNoTablet > 0) {
                if (fotosEnviadas > 0) Spacer(Modifier.height(8.dp))
                TextoApoio(
                    "$fotosParadasNoTablet foto(s) estao NO TABLET e o servidor ainda nao aceitou. " +
                        "Tento a cada 30 s e de novo ao finalizar; se sobrar, o app avisa por voz.",
                    Tom.ATENCAO,
                )
            }
            if (quadrosMarcados > 0) {
                if (fotosEnviadas > 0 || fotosParadasNoTablet > 0) Spacer(Modifier.height(8.dp))
                TextoApoio(
                    "$quadrosMarcados captura(s) NÃO viraram foto: os óculos recusaram e ficou só a " +
                        "marca no vídeo. O servidor tenta recortar o quadro ao finalizar — se não " +
                        "conseguir, essas imagens não entram no laudo. Confira no painel antes de assinar.",
                    Tom.ATENCAO,
                )
            }
        } else {
            TextoApoio(
                if (assistenteIa) {
                    "Toque no botão, use a haste dos óculos, ou peça ao assistente " +
                        "com suas palavras (\"registra uma foto disso\")."
                } else {
                    "Toque no botão, use a haste dos óculos, ou fale \"capturar\"."
                },
            )
        }

        if (previewCamera != null) {
            Spacer(Modifier.height(12.dp))
            previewCamera()
        }

        Spacer(Modifier.height(14.dp))
        // O maior alvo da tela: é o botão que o dedo procura com a luva.
        BotaoPrimario(
            texto = "Tirar foto",
            icone = R.drawable.ic_pv_camera,
            habilitado = podeCapturar,
            grande = true,
            onClick = onFoto,
        )

        if (assistenteIa && podeCapturar) {
            AvisoEscuta(
                titulo = "Assistente ouvindo",
                detalhe = "fale normalmente — ele captura e encerra a pedido",
            )
        } else if (vozAtiva && podeCapturar) {
            AvisoEscuta(
                titulo = if (ouvindoPelosOculos) "Óculos ouvindo" else "Celular ouvindo",
                detalhe = "diga \"capturar\" ou \"finalizar\"",
                acao = "Parar voz",
                onAcao = onVoz,
            )
        }

        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            // O botão de palavras soltas só aparece SEM assistente e enquanto a
            // voz está parada: ligada, o "Parar voz" mora no aviso vermelho acima.
            if (!assistenteIa && !vozAtiva) {
                BotaoContorno(
                    texto = "Comando de voz",
                    icone = R.drawable.ic_pv_mic,
                    habilitado = podeCapturar,
                    modifier = Modifier.weight(1f),
                    onClick = onVoz,
                )
            }
            BotaoContorno(
                texto = if (gravandoAudio) "Parar narração" else "Narração",
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
                texto = if (finalizando) "Finalizando..." else "Finalizar sessão e gerar laudo",
                icone = R.drawable.ic_pv_custodia,
                habilitado = !finalizando,
                tom = Tom.ERRO,
                onClick = onFinalizar,
            )
        }
    }
}

/** Uma foto da perícia como a tela precisa dela: id, miniatura e de onde veio. */
private data class FotoNaTela(
    val id: String,
    val miniatura: android.graphics.Bitmap?,
    /** true = não é foto dos óculos, é quadro recortado do vídeo (720p). */
    val doVideo: Boolean,
    val kb: Int,
    /** requestId da captura: casa a foto que o TABLET já mostrou com a mesma
     *  foto vinda do servidor, para não aparecer duas vezes na galeria. */
    val requestId: String? = null,
)

/** Decodifica o JPEG já REDUZIDO. Sem isto, meia dúzia de fotos de 3 MB em
 *  resolução cheia derrubaria o app por memória no meio da perícia. */
private fun miniaturaDe(bytes: ByteArray, alvoPx: Int = 480): android.graphics.Bitmap? {
    val medir = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, medir)
    var escala = 1
    val maior = maxOf(medir.outWidth, medir.outHeight)
    // maior <= 0 = decode de medição falhou; alvoPx <= 0 nunca acontece, mas o
    // laço não pode depender disso para terminar.
    if (maior > 0 && alvoPx > 0) while (maior / (escala * 2) >= alvoPx) escala *= 2
    val opcoes = android.graphics.BitmapFactory.Options().apply {
        inSampleSize = escala
        // JPEG não tem canal alfa: RGB_565 gasta metade da memória de ARGB_8888.
        // Numa perícia com 40 capturas isso é a diferença entre 20 MB e 45 MB
        // de bitmaps vivos ao lado do receptor de vídeo.
        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
    }
    return runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opcoes) }.getOrNull()
}

/**
 * FOTOS DA PERÍCIA — miniaturas do que o SERVIDOR já recebeu, embaixo do vídeo.
 *
 * Pedido de campo (08/09/2026): a foto sai dos óculos direto para o servidor e
 * o perito nunca a via na bancada. Quando saía escura, tremida ou fora de
 * enquadramento, ele só descobria no painel, horas depois, com o vestígio já
 * lacrado de volta. Aqui ele vê e refaz na hora.
 */
@Composable
private fun CartaoFotosDaPericia(
    fotos: List<FotoNaTela>,
    /** Quantas o app contou e ainda não voltaram do servidor. */
    esperando: Int,
    onAmpliar: (FotoNaTela) -> Unit,
) {
    if (fotos.isEmpty() && esperando <= 0) return
    CartaoPv {
        CabecalhoCartao(
            titulo = "Fotos da perícia",
            etiqueta = if (fotos.isEmpty()) "subindo…" else "${fotos.size} no servidor",
            tomEtiqueta = if (fotos.isEmpty()) Tom.ATENCAO else Tom.OK,
            grande = true,
        )
        TextoApoio("Confira agora se saiu boa — foco, luz e enquadramento. Toque para ampliar.")
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            fotos.forEachIndexed { i, f ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(width = 132.dp, height = 100.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(Color(0xFF0E141C))
                            .clickable { onAmpliar(f) },
                        contentAlignment = Alignment.Center,
                    ) {
                        val bmp = f.miniatura
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "foto ${i + 1}",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Text("…", color = Color.White, style = MaterialTheme.typography.titleLarge)
                        }
                        if (f.doVideo) {
                            Text(
                                "DO VÍDEO",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .background(MaterialTheme.colorScheme.error)
                                    .padding(horizontal = 4.dp),
                            )
                        }
                    }
                    Text(
                        "${i + 1}${if (f.kb > 0) " · ${f.kb} kB" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (esperando > 0) {
                Box(
                    modifier = Modifier
                        .size(width = 132.dp, height = 100.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(Color(0xFF1A2430)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "subindo\n$esperando",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                }
            }
        }
        if (fotos.any { it.doVideo }) {
            Spacer(Modifier.height(8.dp))
            TextoApoio(
                "As marcadas DO VÍDEO não são foto dos óculos: são quadros recortados da " +
                    "gravação, em resolução menor. Se alguma for importante, refaça a foto.",
                Tom.ATENCAO,
            )
        }
    }
}

/** A foto em tela cheia, para julgar foco e luz de verdade. Toque fecha. */
@Composable
private fun FotoAmpliada(
    foto: FotoNaTela,
    /** Resolução alta, quando já baixou; até lá mostra a miniatura. */
    emAlta: android.graphics.Bitmap?,
    onFechar: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xEE000000))
            .clickable(onClick = onFechar),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = emAlta ?: foto.miniatura
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "foto ampliada",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
        Text(
            when {
                emAlta == null -> "carregando em resolução cheia…"
                foto.doVideo -> "quadro do vídeo · toque para fechar"
                else -> "toque para fechar"
            },
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
        )
    }
}

/**
 * A janela onde o decodificador desenha. Um SurfaceView cru, não um player: o
 * MediaCodec escreve direto na Surface, sem passar bitmap por Kotlin.
 *
 * A Surface nasce e morre com a view (o Android a destrói ao apagar a tela ou
 * ao rolar a lista para longe), e o decodificador precisa saber das duas
 * coisas — sem o surfaceDestroyed ele continuaria entregando quadro a uma
 * superfície morta e o codec cairia em IllegalStateException.
 */
@Composable
private fun VisorAoVivoDosOculos(
    decodificador: com.example.peritavision.rtmp.DecodificadorDeVideo,
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            android.view.SurfaceView(ctx).apply {
                holder.addCallback(object : android.view.SurfaceHolder.Callback {
                    override fun surfaceCreated(h: android.view.SurfaceHolder) {
                        decodificador.definirSuperficie(h.surface)
                    }
                    override fun surfaceChanged(h: android.view.SurfaceHolder, formato: Int, largura: Int, altura: Int) {
                        decodificador.definirSuperficie(h.surface)
                    }
                    override fun surfaceDestroyed(h: android.view.SurfaceHolder) {
                        decodificador.definirSuperficie(null)
                    }
                })
            }
        },
    )
}

@Composable
private fun CartaoVisaoOculos(
    urlFlv: String?,
    aoVivo: Boolean,
    protocolo: String,
    /** A imagem ao vivo (SurfaceView do decodificador); null no modo servidor. */
    imagem: (@Composable () -> Unit)? = null,
    imagemEstado: com.example.peritavision.rtmp.DecodificadorDeVideo.Estado? = null,
    /** Estado do receptor local (modo "tablet"); null no modo servidor. */
    receptor: com.example.peritavision.rtmp.ReceptorDeVideo.Estado? = null,
    perfil: String = "",
    subindo: Int = 0,
    subidos: Int = 0,
    comFalha: Int = 0,
) {
    if (receptor != null) {
        // MODO TABLET: sem player (ainda) — o que importa no teste de campo é
        // saber que os quadros estão chegando, a que taxa e com que tamanho.
        val recebendo = receptor.publicando && System.currentTimeMillis() - receptor.ultimoQuadroMs < 4_000
        var segundos by remember(receptor.chave) { mutableIntStateOf(0) }
        LaunchedEffect(receptor.chave, receptor.publicando) {
            while (receptor.publicando) { delay(1000); segundos++ }
        }
        val cronometro = "%02d:%02d".format(segundos / 60, segundos % 60)
        val mb = receptor.bytes / 1_000_000.0
        val legenda = when {
            !receptor.ligado -> receptor.erro ?: "Receptor do tablet desligado."
            receptor.erro != null -> receptor.erro
            receptor.publicando && !recebendo -> "Óculos conectados ao tablet, mas sem quadro há mais de 4 s."
            receptor.publicando -> "Recebendo direto dos óculos pela Wi-Fi da bancada — sem internet no caminho."
            // Diagnóstico do teste de campo: se ninguém abriu TCP na porta, a
            // Wi-Fi está isolando os aparelhos; se abriu e não publicou, o
            // problema é o handshake ou o comando que foi aos óculos.
            receptor.ultimoCliente != null ->
                "Os óculos (${receptor.ultimoCliente}) chegaram ao tablet mas não começaram a publicar — handshake."
            else -> "Aguardando os óculos publicarem em rtmp://${receptor.ip}:${receptor.porta}/pv/… (ninguém chegou à porta ainda)"
        }
        MolduraVisor(
            aoVivo = recebendo,
            cronometro = cronometro,
            fonte = "TABLET · $perfil",
            protocolo = if (protocolo.isBlank()) "" else "PROT $protocolo",
            legenda = legenda,
            conteudo = {
                // A IMAGEM primeiro, ocupando o visor. Os números ficam por
                // cima, pequenos, num canto: eles continuam sendo o diagnóstico
                // (quantos quadros, quantos MB, qual segmento), mas quem manda
                // na tela agora é o que os óculos estão vendo.
                // `imagemEstado != null &&` explícito: com `imagemEstado?.x == true`
                // o Kotlin não faz smart cast e o acesso seguinte não compila.
                val comImagem = imagem != null && imagemEstado != null &&
                    imagemEstado.decodificando && (imagemEstado.quadrosNaTela > 0 || recebendo)
                if (imagem != null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) { imagem() }
                    }
                }
                Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = if (comImagem) Arrangement.Bottom else Arrangement.Center,
                    horizontalAlignment = if (comImagem) Alignment.Start else Alignment.CenterHorizontally,
                ) {
                    if (!comImagem) {
                        Text(
                            text = if (receptor.publicando) "%.1f".format(receptor.quadrosPorSegundo) else "—",
                            style = MaterialTheme.typography.displayMedium,
                            color = if (recebendo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("quadros por segundo", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                    } else {
                        Text(
                            "%.1f q/s".format(receptor.quadrosPorSegundo) +
                                (imagemEstado?.let { if (it.largura > 0) " · ${it.largura}x${it.altura}" else "" } ?: ""),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                        )
                    }
                    Text(
                        "${receptor.quadros} quadros · %.1f MB · segmento ${receptor.segmentosFechados + if (receptor.publicando) 1 else 0}".format(mb),
                        style = if (comImagem) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium,
                        color = if (comImagem) Color.White else MaterialTheme.colorScheme.onSurface,
                    )
                    imagemEstado?.erro?.let {
                        Text("imagem: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    val envio = buildString {
                        if (subindo > 0) append("subindo $subindo · ")
                        if (subidos > 0) append("no servidor $subidos · ")
                        if (comFalha > 0) append("FALHOU $comFalha · ")
                    }.trimEnd(' ', '·')
                    if (envio.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(envio, style = MaterialTheme.typography.labelMedium,
                            color = if (comFalha > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    receptor.ultimoMotivo?.let {
                        Spacer(Modifier.height(4.dp))
                        Text("último segmento: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
        )
        return
    }
    val transmitindo = aoVivo && urlFlv != null

    // Cronômetro da transmissão: zera quando a URL muda (nova sessão).
    var segundos by remember(urlFlv) { mutableIntStateOf(0) }
    LaunchedEffect(urlFlv, transmitindo) {
        while (transmitindo) {
            delay(1000)
            segundos++
        }
    }
    val cronometro = "%02d:%02d".format(segundos / 60, segundos % 60)

    val player: (@Composable () -> Unit)? = if (!transmitindo) null else {
        {
            val context = LocalContext.current
            // Player recriado quando a URL muda (nova sessão) e liberado quando o
            // cartão sai de cena — sem isso o decodificador fica preso em segundo plano.
            val exo = remember(urlFlv) {
                androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
                    setMediaItem(androidx.media3.common.MediaItem.fromUri(urlFlv!!))
                    // O monitor é só visual: com o áudio ligado, o tablet toca a
                    // voz do perito de volta (eco) e alimenta o loop em que a IA
                    // se ouve. Vídeo segue normal; áudio fica mudo.
                    volume = 0f
                    prepare()
                    playWhenReady = true
                }
            }
            DisposableEffect(exo) { onDispose { exo.release() } }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    androidx.media3.ui.PlayerView(ctx).apply {
                        useController = false
                        this.player = exo
                    }
                },
                update = { view -> view.player = exo },
            )
        }
    }

    MolduraVisor(
        aoVivo = transmitindo,
        cronometro = cronometro,
        fonte = "MENTRA LIVE · 1080p",
        protocolo = if (protocolo.isBlank()) "" else "PROT $protocolo",
        legenda = if (transmitindo) {
            "Atraso de alguns segundos é normal — o vídeo passa pelo servidor."
        } else {
            "O vídeo dos óculos aparece aqui, ao vivo, assim que a transmissão começar."
        },
        conteudo = player,
    )
}

/** Uma seção do laudo em preenchimento, já resolvida para a tela. */
private data class SecaoLaudo(
    val numero: Int,
    val titulo: String,
    val preenchida: Boolean,
    val dica: String? = null,
    val campos: List<Pair<String, String>> = emptyList(),
    val itens: List<String> = emptyList(),
)

/**
 * Laudo pericial em preenchimento — as 8 seções do modelo POLITEC-MT, com o
 * que a sessão já sabe. Seções 1–3 vêm do ATENA (ou da ficha do lacre) na
 * abertura; 5 e 6 crescem com as fotos seladas e a narração do perito; 4, 7 e
 * 8 ficam pendentes até a revisão no painel. É acompanhamento: o laudo de
 * verdade continua sendo montado pelo servidor no Finalizar.
 */
@Composable
private fun CartaoLaudoEmPreenchimento(
    protocolo: String,
    caso: BackendClient.CasoAtena?,
    ficha: BackendClient.FichaLacre?,
    fotosSeladas: Int,
    narracoes: List<String>,
    achados: List<String>,
) {
    val autoridade = caso?.autoridade ?: ficha?.solicitante
    val orgao = caso?.unidadeRequisitante ?: ficha?.unidadeRequisitante
    val dataOcorrencia = caso?.dataOcorrencia ?: ficha?.dataOcorrencia
    val vitima = ficha?.vitima
    val materiais = caso?.materiais?.ifEmpty { null } ?: ficha?.materiais ?: emptyList()
    val objetivos = caso?.exames?.ifEmpty { null }
        ?: caso?.naturezas?.ifEmpty { null }
        ?: ficha?.naturezas ?: emptyList()

    val historico = buildList {
        autoridade?.let { add("Autoridade" to it) }
        orgao?.let { add("Órgão solicitante" to it) }
        dataOcorrencia?.let { add("Data da ocorrência" to it) }
        vitima?.let { add("Vítima" to it) }
    }

    val secoes = listOf(
        SecaoLaudo(1, "Histórico", preenchida = historico.isNotEmpty(),
            dica = "Vem do ATENA ao abrir a perícia.", campos = historico),
        SecaoLaudo(2, "Materiais recebidos", preenchida = materiais.isNotEmpty(),
            dica = "Vem do ATENA ao abrir a perícia.", itens = materiais),
        SecaoLaudo(3, "Objetivos dos exames", preenchida = objetivos.isNotEmpty(),
            dica = "Vem do ATENA ao abrir a perícia.", itens = objetivos),
        SecaoLaudo(4, "Materiais e métodos", preenchida = false,
            dica = "Redigida na revisão do laudo, a partir do método padrão."),
        SecaoLaudo(5, "Resultados", preenchida = fotosSeladas > 0 || achados.isNotEmpty(),
            dica = "Os achados que você enunciar (\"item um, sangue negativo\") entram aqui; as fotos viram figuras.",
            campos = listOf("Figuras" to "$fotosSeladas foto(s) selada(s) por hash", "Achados" to "${achados.size} registrado(s)"),
            itens = achados.takeLast(5)),
        SecaoLaudo(6, "Considerações", preenchida = narracoes.isNotEmpty(),
            dica = "O que você narrar na bancada entra aqui.",
            itens = narracoes.takeLast(3).map { if (it.length > 180) it.take(180) + "…" else it }),
        SecaoLaudo(7, "Conclusão", preenchida = false,
            dica = "Só o perito escreve — no painel web, na revisão."),
        SecaoLaudo(8, "Disposições finais", preenchida = false,
            dica = "Texto padrão do modelo, incluído na geração."),
    )
    val preenchidas = secoes.count { it.preenchida }
    val pct = preenchidas * 100 / secoes.size

    CartaoPv {
        CabecalhoCartao(
            titulo = "Laudo pericial — em preenchimento",
            etiqueta = "$pct%",
            tomEtiqueta = if (pct >= 100) Tom.OK else Tom.ATENCAO,
            grande = true,
        )
        Text(
            "Protocolo ${protocolo.ifBlank { "—" }} · modelo POLITEC-MT",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(12.dp))
        BarraProgresso(pct / 100f)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            secoes.forEach { sec ->
                SecaoLaudoPv(
                    numero = sec.numero,
                    titulo = sec.titulo,
                    preenchida = sec.preenchida,
                    dica = sec.dica,
                ) {
                    sec.campos.forEach { (rotulo, valor) -> LinhaCampo(rotulo, valor) }
                    sec.itens.forEach { item ->
                        Text(
                            "• $item",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        TextoApoio("Ao finalizar, o servidor monta o laudo com tudo isso — revisão e assinatura ficam no painel.")
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
            grande = true,
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
    /** o circuito de vídeo está de pé (quadros chegando ao servidor) */
    enxergando: Boolean,
    /** a IA está REALMENTE olhando agora (janela aberta por pedido do perito) */
    olhandoAgora: Boolean,
    /** roteiro em uso ("Trilha A — Faca / perfurocortante"); null = indefinido */
    trilha: String?,
    /** a IA está na triagem, perguntando o tipo de exame ao perito */
    perguntandoTrilha: Boolean,
    /** modo da IA: conversa | silencio | pausa */
    modo: String,
    /** troca de modo pelo toque (reserva — o perito de luvas usa a voz) */
    onModo: (String) -> Unit,
    /** diagnóstico da saída de voz ("→ Mentra Live · 12 trecho(s)", "ERRO: ...") */
    voz: String,
    perito: String,
    resposta: String,
    onAlternar: () -> Unit,
) {
    CartaoPv {
        CabecalhoCartao(
            titulo = "Assistente de voz",
            // Estados de verdade, não dois: desligado / ativo (câmera em
            // repouso, o normal) / olhando agora / sem imagem.
            // Estados de verdade: desligado / perguntando a trilha / em conversa
            // (olhando ou não) / ouvindo em silêncio. "Sem imagem" vira aviso
            // no corpo do cartão, não etiqueta.
            etiqueta = when {
                !ativo -> "desligado"
                perguntandoTrilha -> "perguntando o exame"
                modo == "pausa" -> "gravação em pausa"
                modo == "silencio" -> "silêncio — ouvindo e registrando"
                olhandoAgora -> "olhando agora"
                else -> "em conversa"
            },
            tomEtiqueta = when {
                !ativo -> Tom.NEUTRO
                perguntandoTrilha -> Tom.ATENCAO
                modo == "pausa" -> Tom.ERRO
                modo == "silencio" -> Tom.NEUTRO
                else -> Tom.OK
            },
            grande = true,
        )
        if (!ativo) {
            TextoApoio(
                "Assistente de voz pelos óculos (Gemini). Liga sozinho quando a sessão " +
                    "abre; aqui você religa se tiver desligado. Uma cópia do áudio vai aos " +
                    "servidores do Google — use apenas em teste/bancada, não em caso real.",
            )
            Spacer(Modifier.height(12.dp))
            BotaoTonal(texto = "Ligar assistente", icone = R.drawable.ic_pv_mic, onClick = onAlternar)
            return@CartaoPv
        }
        // Roteiro: qual prompt está guiando esta sessão (definido na triagem
        // pela voz do perito, fixado em Configurações, ou mantido da memória).
        when {
            perguntandoTrilha -> TextoApoio(
                "Perguntando o tipo de exame — responda em voz alta: \"objeto cortante\" ou \"peça íntima\".",
                Tom.ATENCAO,
            )
            trilha != null -> LinhaCampo("Roteiro", trilha)
        }
        if (voz.isNotBlank()) {
            TextoApoio("Voz $voz", if (voz.startsWith("ERRO")) Tom.ERRO else null)
        }
        if (!enxergando) {
            TextoApoio(
                "Sem imagem dos óculos: o assistente ouve, mas NÃO está vendo a bancada — " +
                    "não confie no que ele disser sobre o material.",
                Tom.ATENCAO,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (perito.isNotBlank()) BalaoConversa(perito, doPerito = true)
        if (resposta.isNotBlank()) BalaoConversa(resposta, doPerito = false)
        if (perito.isBlank() && resposta.isBlank()) {
            TextoApoio(
                when (modo) {
                    "silencio" -> "Ouvindo e registrando achados em silêncio. \"O que foi salvo?\" ela lê. Diga a palavra de conversa para ela voltar a falar."
                    "pausa" -> "Gravação em pausa: o vídeo dos óculos está parado, nada do que for dito vai para o laudo e nenhum comando executa. Diga \"assistente\" ou \"silêncio\" para voltar."
                    else -> "Fale normalmente: ela responde, conduz o roteiro e registra os achados. Diga a palavra de silêncio para trabalhar sem interrupção."
                },
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (modo == "conversa") BotaoTonal("Conversar", modifier = Modifier.weight(1f), onClick = {})
            else BotaoContorno("Conversar", modifier = Modifier.weight(1f), onClick = { onModo("conversa") })
            if (modo == "silencio") BotaoTonal("Silêncio", modifier = Modifier.weight(1f), onClick = {})
            else BotaoContorno("Silêncio", modifier = Modifier.weight(1f), onClick = { onModo("silencio") })
            if (modo == "pausa") BotaoTonal("Pausa", modifier = Modifier.weight(1f), onClick = {})
            else BotaoContorno("Pausa", tom = Tom.ERRO, modifier = Modifier.weight(1f), onClick = { onModo("pausa") })
        }
        Spacer(Modifier.height(12.dp))
        BotaoContorno(
            texto = "Desligar assistente",
            icone = R.drawable.ic_pv_stop,
            tom = Tom.ERRO,
            onClick = onAlternar,
        )
    }
}

@Composable
private fun CartaoEvidencia(ev: Evidencia) {
    CartaoPv {
        CabecalhoCartao(
            titulo = "Última evidência",
            grande = true,
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
