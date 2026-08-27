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

android {
    namespace = "com.example.peritavision"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.peritavision"
        minSdk = 30
        // Android 15 (API 35): versao do tablet da bancada. compileSdk continua
        // 36 (exigencia das libs Compose/AGP atuais para COMPILAR); o targetSdk
        // e o que define contra qual Android o app declara rodar.
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "PV_BACKEND", segredo("pv.backend", "https://peritavision.facilmova.com.br"))
        buildConfigField("String", "PV_MATRICULA", segredo("pv.matricula"))
        buildConfigField("String", "PV_SENHA", segredo("pv.senha"))
        buildConfigField("String", "PV_PROTOCOLO", segredo("pv.protocolo"))
        // Assistente IA de bancada (ponte Gemini Live). Vazio = recurso oculto.
        buildConfigField("String", "PV_PONTE_URL", segredo("pv.ponte"))
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
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