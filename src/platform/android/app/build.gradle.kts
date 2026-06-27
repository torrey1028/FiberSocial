import java.io.ByteArrayOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun gitVersionName(): String {
    val hash = ByteArrayOutputStream().also { out ->
        exec { commandLine("git", "rev-parse", "--short", "HEAD"); standardOutput = out }
    }.toString().trim()
    val dirty = ByteArrayOutputStream().also { out ->
        exec { commandLine("git", "status", "--porcelain"); standardOutput = out }
    }.toString().trim().isNotEmpty()
    return if (dirty) "1.0.$hash.dirty" else "1.0.$hash"
}

android {
    namespace = "com.autom8ed"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.autom8ed"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = gitVersionName()
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

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":common"))
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
}
