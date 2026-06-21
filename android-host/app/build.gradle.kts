plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

dependencies {
    implementation("com.github.mwiede:jsch:2.28.2")
    implementation("androidx.core:core-ktx:1.13.1")
}

android {
    namespace = "com.mobileagent.host"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mobileagent.host"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0-alpha"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}
