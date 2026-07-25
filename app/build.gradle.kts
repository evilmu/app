plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.gov.bomsucesso.threadsdownloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.gov.bomsucesso.threadsdownloader"
        minSdk = 26
        targetSdk = 35
        versionCode = 30
        versionName = "3.0.0"
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
    compileOnly("de.robv.android.xposed:api:82")
}
