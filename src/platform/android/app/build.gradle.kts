import java.io.ByteArrayOutputStream
import java.util.Properties

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

val localProps = Properties().also { props ->
    rootProject.file("local.properties").takeIf { it.exists() }
        ?.inputStream()?.use { props.load(it) }
}

android {
    namespace = "com.autom8ed.fibersocial"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.autom8ed.fibersocial"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = gitVersionName()
        buildConfigField("String", "RAVELRY_CLIENT_ID", "\"${localProps.getProperty("ravelry.client_id", "")}\"")
        buildConfigField("String", "RAVELRY_CLIENT_SECRET", "\"${localProps.getProperty("ravelry.client_secret", "")}\"")
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
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":common"))
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.1")
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.ktor:ktor-client-android:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // Registers the test ComponentActivity that createAndroidComposeRule launches.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// createAndroidComposeRule resolves the test ComponentActivity from the merged
// manifest, and ui-test-manifest only contributes it to the debug variant —
// on testReleaseUnitTest these tests die with "Unable to resolve activity".
tasks.withType<Test>().configureEach {
    if (name == "testReleaseUnitTest") {
        exclude(
            "**/CloseDrawerOnBackTest*",
            "**/TopicDetailScreenBackTest*",
            "**/SettingsScreenTest*",
            "**/GroupDrawerTest*",
        )
    }
}
