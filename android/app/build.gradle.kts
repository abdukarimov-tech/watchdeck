import com.google.protobuf.gradle.proto

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.protobuf")
}

android {
    namespace = "app.pulse"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.pulse"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.1"
    }

    // релизное подписание из env (см. RELEASE.md); без env — unsigned APK
    val storeFilePath = System.getenv("PULSE_STORE_FILE")
    signingConfigs {
        if (storeFilePath != null) {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("PULSE_STORE_PASSWORD")
                keyAlias = System.getenv("PULSE_KEY_ALIAS")
                keyPassword = System.getenv("PULSE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (storeFilePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    // контракт протокола переиспользуем прямо из корня репозитория
    sourceSets {
        getByName("main") {
            // One updater shared with the iOS Swift package.
            assets.srcDir("../../ios/PulseCore/Sources/PulseCore/Resources")
            proto {
                srcDir("../../proto")
            }
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.29.3"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") { option("lite") }
                create("kotlin") { option("lite") }
            }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    // нужен lint-правилу ActivityResult (InvalidFragmentVersionForActivityResult)
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // SSH-клиент для установки агента на сервер из приложения
    implementation("com.hierynomus:sshj:0.38.0")
    // явно нужен для подмены встроенного "BC"-провайдера в PulseApp (см. там);
    // версия совпадает с той, что тянет sshj
    implementation("org.bouncycastle:bcprov-jdk18on:1.75")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("com.google.protobuf:protobuf-kotlin-lite:4.29.3")
    // пуш-сервис; для реальной работы нужен google-services.json (см. README)
    implementation("com.google.firebase:firebase-messaging:24.1.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
