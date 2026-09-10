import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Segredos de desenvolvimento ficam em local.properties (gitignorado) — nunca
// no codigo-fonte. Cada chave vira um campo do BuildConfig; sem a chave, o
// campo fica vazio e o perito digita na tela.
val propsLocais = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun segredo(chave: String, padrao: String = ""): String =
    "\"" + (propsLocais.getProperty(chave) ?: padrao) + "\""

// VERSÃO do aplicativo: app/versao.properties (versionado). O publicar_apk.ps1
// avança o versionCode a cada publicação; o versionName é o que o perito vê na
// página /app e em Configurações.
val propsVersao = Properties().apply {
    file("versao.properties").inputStream().use { load(it) }
}
val versaoCodigo = propsVersao.getProperty("versionCode").trim().toInt()
val versaoNome = propsVersao.getProperty("versionName").trim()

// ASSINATURA DE RELEASE: a chave fica FORA do repositório, apontada no
// local.properties (pv.keystore, pv.keystore.senha, pv.chave.alias,
// pv.chave.senha). Sem ela o assembleRelease falha de propósito — um APK sem
// assinatura não instala, e um assinado com chave de debug não atualiza o que
// já está nos tablets.
val keystoreCaminho = propsLocais.getProperty("pv.keystore")?.trim().orEmpty()
val temChaveDeRelease = keystoreCaminho.isNotEmpty() && file(keystoreCaminho).exists()

android {
    namespace = "br.com.facilmova.peritavision"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "br.com.facilmova.peritavision"
        minSdk = 30
        // Android 15 (API 35): versao do tablet da bancada. compileSdk continua
        // 36 (exigencia das libs Compose/AGP atuais para COMPILAR); o targetSdk
        // e o que define contra qual Android o app declara rodar.
        targetSdk = 35
        versionCode = versaoCodigo
        versionName = versaoNome

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "PV_BACKEND", segredo("pv.backend", "https://peritavision.facilmova.com.br"))
        buildConfigField("String", "PV_MATRICULA", segredo("pv.matricula"))
        buildConfigField("String", "PV_SENHA", segredo("pv.senha"))
        buildConfigField("String", "PV_PROTOCOLO", segredo("pv.protocolo"))
        // Assistente IA de bancada (ponte Gemini Live). Vazio = recurso oculto.
        buildConfigField("String", "PV_PONTE_URL", segredo("pv.ponte"))
    }

    signingConfigs {
        if (temChaveDeRelease) {
            create("release") {
                storeFile = file(keystoreCaminho)
                storePassword = propsLocais.getProperty("pv.keystore.senha").orEmpty()
                keyAlias = propsLocais.getProperty("pv.chave.alias").orEmpty()
                keyPassword = propsLocais.getProperty("pv.chave.senha").orEmpty()
            }
        }
    }
    buildTypes {
        release {
            optimization {
                enable = false
            }
            if (temChaveDeRelease) signingConfig = signingConfigs.getByName("release")
            // O APK distribuído NUNCA leva login de teste: estes campos vêm
            // preenchidos do local.properties no debug e aqui são zerados.
            buildConfigField("String", "PV_MATRICULA", "\"\"")
            buildConfigField("String", "PV_SENHA", "\"\"")
            buildConfigField("String", "PV_PROTOCOLO", "\"\"")
        }
    }
    compileOptions {
        // Mentra Bluetooth SDK exige Java 17.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Evita conflito de libs nativas do stack de audio/transcricao do SDK Mentra.
    packaging {
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
            pickFirsts += "**/libonnxruntime.so"
            pickFirsts += "**/libonnxruntime4j_jni.so"
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("androidx.camera:camera-video:1.3.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // Mentra Bluetooth SDK — conexao BLE direta com o Mentra Live pelo SEU app
    // (sem o app da Mentra). Versao em gradle.properties (mentraSdkVersion).
    implementation("com.mentraglass:bluetooth-sdk:${project.property("mentraSdkVersion")}")
    // WebSocket para transmitir o audio DOS OCULOS ao backend (ASR proprio).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // ExoPlayer (media3): toca o HTTP-FLV do node-media-server — o cartao
    // "Visao dos oculos" mostra ao vivo o mesmo stream que vai para o backend.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    // Leitor de codigo de barras do lacre (tela pronta do Google Play Services).
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

}
// Sem chave de release, o assembleRelease para AQUI, com a instrução — e não
// no tablet, com "app não instalado".
gradle.taskGraph.whenReady {
    val pedeRelease = allTasks.any { it.project == project && it.name.contains("Release") && it.name.startsWith("assemble") }
    if (pedeRelease && !temChaveDeRelease) {
        throw GradleException(
            "Release sem chave de assinatura. Crie a chave uma vez:\n" +
            "  keytool -genkeypair -v -keystore C:\\dev\\peritavision-release.jks -alias peritavision -keyalg RSA -keysize 2048 -validity 10000\n" +
            "e aponte no local.properties: pv.keystore=C:/dev/peritavision-release.jks, pv.keystore.senha=..., pv.chave.alias=peritavision, pv.chave.senha=..."
        )
    }
}
