plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.gov.bomsucesso.threadsdownloader"
    compileSdk = 35

    signingConfigs {
        create("stableDebug") {
            storeFile = rootProject.file("ci/threads-enhancer.keystore")
            storePassword = "threads123"
            keyAlias = "threads-enhancer"
            keyPassword = "threads123"
        }
    }

    defaultConfig {
        applicationId = "br.gov.bomsucesso.threadsdownloader"
        minSdk = 26
        targetSdk = 35
        versionCode = 50
        versionName = "3.5.0-native-menu"
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("stableDebug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = false
        buildConfig = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // ReLSPosed usa a API Xposed legada.
    compileOnly("de.robv.android.xposed:api:82")
}
