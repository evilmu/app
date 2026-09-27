plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "br.gov.bomsucesso.threadsdownloader"
    compileSdk = 35
    defaultConfig {
        applicationId = "br.gov.bomsucesso.threadsdownloader.inlinepost"
        minSdk = 26
        targetSdk = 35
        versionCode = 38
        versionName = "3.7.0-inline-post"
    }
    buildFeatures { buildConfig = true }
    kotlinOptions { jvmTarget = "17" }
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
    testImplementation("junit:junit:4.13.2")
}
