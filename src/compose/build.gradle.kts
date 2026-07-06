import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // The framework the Xcode project (src/platform/ios) embeds via the
    // embedAndSignAppleFrameworkForXcode build phase. Static: single consumer, no
    // dynamic-framework embedding needed. :common links in transitively.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":common"))
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            // Only for the experimental pullRefresh APIs in ui/PullToRefresh.kt.
            implementation(compose.material)
            api(compose.ui)
            implementation(compose.components.resources)
            // Multiplatform BackHandler (replaces androidx.activity.compose.BackHandler).
            implementation("org.jetbrains.compose.ui:ui-backhandler:1.11.1")
            // Final release of the KMP icons artifact (discontinued upstream; the icons
            // are plain ImageVectors, independent of the Compose version). Newer
            // material3 no longer pulls icons in transitively. api: the app module's
            // MainActivity also renders icons.
            api("org.jetbrains.compose.material:material-icons-core:1.7.3")
            api("io.coil-kt.coil3:coil-compose:3.5.0")
        }
        androidMain.dependencies {
            // Image picker actual (rememberLauncherForActivityResult).
            implementation("androidx.activity:activity-compose:1.9.0")
        }
        iosMain.dependencies {
            // Coil has no built-in network fetcher; on iOS it rides the Ktor 2 Darwin
            // engine :common already uses (Android keeps its OkHttp fetcher in :app).
            implementation("io.coil-kt.coil3:coil-network-ktor2:3.5.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
        }
        androidUnitTest.dependencies {
            implementation("junit:junit:4.13.2")
            implementation("org.robolectric:robolectric:4.16")
            // androidx version matching the Compose version CMP 1.11.1 pins.
            implementation("androidx.compose.ui:ui-test-junit4:1.11.2")
        }
    }
}

dependencies {
    // Registers the test ComponentActivity that createAndroidComposeRule launches.
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.11.2")
}

android {
    namespace = "com.autom8ed.fibersocial.composeapp"
    // 36 required by Coil 3.5; 35+ by the Compose 1.11 artifacts that Compose Multiplatform 1.11 pins.
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

compose.resources {
    packageOfResClass = "com.autom8ed.fibersocial.composeapp.resources"
}

// createAndroidComposeRule resolves the test ComponentActivity from the merged
// manifest, and ui-test-manifest only contributes it to the debug variant — on
// testReleaseUnitTest every rule-based test dies with "Unable to resolve activity".
// (The old :app world excluded only 10 classes; that list had gone stale because
// nothing in CI runs release unit tests. This is the full createAndroidComposeRule
// set — only the pure-logic tests, e.g. PostBodyTest, run on the release variant.)
tasks.withType<Test>().configureEach {
    if (name == "testReleaseUnitTest") {
        exclude(
            "**/CloseDrawerOnBackTest*",
            "**/DeletePostUiTest*",
            "**/EventDetailScreenPullToRefreshTest*",
            "**/EventsScreenPullToRefreshTest*",
            "**/FeedErrorStateTest*",
            "**/FeedFabsTest*",
            "**/FiberSocialThemeTest*",
            "**/GroupDrawerTest*",
            "**/NewTopicScreenTest*",
            "**/ProjectPhotoPickerDialogTest*",
            "**/PullToRefreshBoxTest*",
            "**/ReplyComposerTest*",
            "**/SettingsScreenTest*",
            "**/TopicCardTest*",
            "**/TopicDetailRouteTest*",
            "**/TopicDetailScreenBackTest*",
            "**/TopicDetailScreenPullToRefreshTest*",
        )
    }
}
