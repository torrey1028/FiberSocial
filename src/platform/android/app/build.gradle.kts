import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun gitVersionName(): String {
    val hash = providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }.standardOutput.asText.get().trim()
    val dirty = providers.exec { commandLine("git", "status", "--porcelain") }.standardOutput.asText.get().trim().isNotEmpty()
    return if (dirty) "1.0.$hash.dirty" else "1.0.$hash"
}

// When the current commit is exactly tagged vMAJOR.MINOR.PATCH (a pushed
// release tag - see release.yml), use that as the real version instead of the
// dev git-hash scheme above, and derive a versionCode that increases
// release-over-release so in-place upgrade installs work.
fun releaseTagVersion(): Triple<Int, Int, Int>? {
    val describe = providers.exec {
        commandLine("git", "describe", "--tags", "--exact-match", "--match", "v[0-9]*.[0-9]*.[0-9]*")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
    val match = Regex("""^v(\d+)\.(\d+)\.(\d+)$""").matchEntire(describe) ?: return null
    val (major, minor, patch) = match.destructured.toList().map {
        it.toIntOrNull() ?: error("Release tag $describe has a component out of Int range")
    }
    // The versionCode packing below gives minor/patch three digits each, so
    // out-of-range components would silently collide with a neighboring
    // version's code (v1.2.1000 == v1.3.0), major >= 2147 overflows Int, and
    // v0.0.0 packs to versionCode 0 which Android rejects. Fail the build
    // instead of shipping a colliding code.
    require(major in 0..2146 && minor in 0..999 && patch in 0..999 && major + minor + patch > 0) {
        "Release tag $describe doesn't fit the versionCode scheme (major <= 2146, minor/patch <= 999, above v0.0.0)"
    }
    return Triple(major, minor, patch)
}

val releaseTag = releaseTagVersion()

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
        versionCode = releaseTag?.let { (major, minor, patch) -> major * 1_000_000 + minor * 1_000 + patch } ?: 1
        versionName = releaseTag?.let { (major, minor, patch) -> "$major.$minor.$patch" } ?: gitVersionName()
        buildConfigField("String", "RAVELRY_CLIENT_ID", "\"${localProps.getProperty("ravelry.client_id", "")}\"")
        buildConfigField("String", "RAVELRY_CLIENT_SECRET", "\"${localProps.getProperty("ravelry.client_secret", "")}\"")
    }

    val releaseStoreFile = localProps.getProperty("release.store.file")
    val releaseSigningConfig = if (!releaseStoreFile.isNullOrBlank()) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(releaseStoreFile)
            storePassword = localProps.getProperty("release.store.password")
            keyAlias = localProps.getProperty("release.key.alias")
            keyPassword = localProps.getProperty("release.key.password")
        }
    } else null

    buildTypes {
        release {
            isMinifyEnabled = false
            releaseSigningConfig?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":common"))
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    // Only for the experimental pullRefresh API (see ui/PullToRefresh.kt) — this
    // project's Material3 version predates the stable PullToRefreshBox.
    implementation("androidx.compose.material:material")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.1")
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("androidx.work:work-testing:2.9.1")
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
            "**/ReplyComposerTest*",
            "**/PullToRefreshBoxTest*",
            "**/TopicDetailScreenPullToRefreshTest*",
            "**/EventsScreenPullToRefreshTest*",
            "**/EventDetailScreenPullToRefreshTest*",
            "**/DeletePostUiTest*",
        )
    }
}
