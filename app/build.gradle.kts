import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Tipo de dispositivo usado no registro na Amazon. Não fica no repositório: quem compila define
// `androidshow.deviceType` no local.properties ou a variável ANDROIDSHOW_DEVICE_TYPE (ver README).
val deviceType: String = run {
    val local = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use(local::load)
    (local.getProperty("androidshow.deviceType") ?: providers.environmentVariable("ANDROIDSHOW_DEVICE_TYPE").orNull)
        .orEmpty().trim()
}
require(deviceType.matches(Regex("[A-Z0-9]*"))) { "androidshow.deviceType inválido: use só letras maiúsculas e dígitos" }

android {
    namespace = "com.androidshow.alexa"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.androidshow.alexa"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Só arm64: o ONNX Runtime traz libs nativas grandes por ABI.
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "DEVICE_TYPE", "\"$deviceType\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Assinado com a chave de debug até existir uma chave de release.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.onnxruntime.android)

    // Renderizador APL oficial (apl-viewhost-android 2024.3) compilado localmente para arm64; ver docs/APL.md.
    implementation(files("libs/apl-release.aar", "libs/common-release.aar", "libs/discovery-standard-release.aar"))
    implementation(libs.glide)
    implementation(libs.guava)
    implementation(libs.auto.value.annotations)
    implementation(libs.java.websocket)
    implementation(libs.androidx.appcompat)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
