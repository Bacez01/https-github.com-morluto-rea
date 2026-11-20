plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildDate: String = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Paris"))
    .format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.FRENCH))

android {
    namespace = "com.radiomonde"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.radiomonde"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.1"
        // Date shown in the "À propos" screen, e.g. "20 novembre 2026" (French time).
        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")
    }

    buildFeatures {
        buildConfig = true
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
