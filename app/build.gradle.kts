import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

// Signierdaten aus local.properties lesen (nicht im Repo, gitignored).
val signingProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasSigning = signingProps.getProperty("storePassword")?.isNotBlank() == true &&
    rootProject.file(signingProps.getProperty("storeFile", "release.keystore")).exists()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "net.bitplane.android.microphone"
    compileSdk = 36

    defaultConfig {
        // Eigene applicationId, damit diese App parallel zur Original-App
        // installiert werden kann und sie nicht überschreibt.
        applicationId = "net.bitplane.android.microphone2speaker"
        minSdk = 23
        targetSdk = 36
        // Eigene Versionierung, klar abgesetzt vom Original (dort 9 / "0.9").
        versionCode = 100
        versionName = "2.0"

        // Build-Zeitstempel, damit in der App sichtbar ist, welcher Build läuft.
        val buildTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = rootProject.file(
                    signingProps.getProperty("storeFile", "release.keystore")
                )
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Nur signieren, wenn Keystore-Daten vorhanden sind. Sonst bleibt der
            // Release-Build unsigniert (z. B. bei Klonen ohne Keystore).
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_21 } }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.activity)
    implementation(libs.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
}
