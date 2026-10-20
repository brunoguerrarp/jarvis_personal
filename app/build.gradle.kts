import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Config da Ponte PC (MQTT). Lida de local.properties (fora do versionamento) ou
// de variáveis de ambiente, com defaults seguros. O token (flespi) é segredo e
// nunca entra no código.
val mqttProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun mqttCfg(name: String, default: String): String =
    mqttProps.getProperty(name) ?: System.getenv(name) ?: default

val mqttHost = mqttCfg("MQTT_HOST", "mqtt.flespi.io")
val mqttPort = mqttCfg("MQTT_PORT", "8883")
val mqttTls = mqttCfg("MQTT_TLS", "true")
val mqttToken = mqttCfg("MQTT_TOKEN", "")
val mqttPrefix = mqttCfg("MQTT_PREFIX", "")

android {
    namespace = "digital.hstech.jarvis"
    compileSdk = 34

    defaultConfig {
        applicationId = "digital.hstech.jarvis"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Ponte com o PC via MQTT (daemon em bridge/). Token vazio = ponte desligada.
        buildConfigField("String", "MQTT_HOST", "\"$mqttHost\"")
        buildConfigField("int", "MQTT_PORT", mqttPort)
        buildConfigField("boolean", "MQTT_TLS", mqttTls)
        buildConfigField("String", "MQTT_TOKEN", "\"$mqttToken\"")
        buildConfigField("String", "MQTT_PREFIX", "\"$mqttPrefix\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Cliente MQTT para a ponte com o PC (daemon em bridge/). Java puro, leve;
    // rodamos dentro do WakeWordService (sem o Android-service descontinuado do Paho).
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    // Palavra de ativação "Jarvis" offline, sem chave e sem o bipe do sistema.
    // Reconhecimento de voz 100% no aparelho (modelo pt-BR embutido em assets).
    implementation("com.alphacephei:vosk-android:0.3.47")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
