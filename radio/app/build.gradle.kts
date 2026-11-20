import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildDate: String = LocalDate.now(ZoneId.of("Europe/Paris"))
    .format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH))

android {
    namespace = "com.radiomonde"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.radiomonde"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.2"
        // Date shown in the "À propos" screen, e.g. "20 novembre 2026" (French time).
        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Same signing key on every build (the default debug key is random on each GitHub runner),
    // so a new version installs over the previous one instead of being refused.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("androidx.annotation:annotation:1.9.1")

    testImplementation("junit:junit:4.13.2")
    // Real org.json implementation: the one in android.jar is only a stub in unit tests.
    testImplementation("org.json:json:20240303")
}
