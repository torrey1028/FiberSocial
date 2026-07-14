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
        // Issue #217: this module's projectDir is src/common/compose/ (see
        // ../../platform/android/settings.gradle.kts). commonMain/commonTest stay under
        // this module's own tree (with an explicit srcDir override to avoid Kotlin's
        // default convention reintroducing a redundant "src" segment, same as :common's
        // build.gradle.kts). androidMain/androidUnitTest and iosMain/iosTest moved out to
        // platform/android/composeApp/ and platform/ios/composeApp/ respectively —
        // platform actuals (and their tests) don't belong inside "common".
        commonMain {
            // srcDir (additive), not setSrcDirs (replaces the whole set) — the Compose
            // Resources plugin registers its generated Res/accessor output dir on this
            // same source set during plugin application, before this block runs;
            // setSrcDirs was wiping that registration out, leaving Res/painterResource
            // unresolved despite prepareComposeResourcesTaskForCommonMain succeeding.
            kotlin.srcDir("commonMain/kotlin")
            dependencies {
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
        }
        androidMain {
            // srcDir, not setSrcDirs — see commonMain's comment above; the Compose
            // Resources plugin also registers a generated actual-resource-collectors
            // dir on androidMain.
            kotlin.srcDir("../../platform/android/composeApp/androidMain/kotlin")
            dependencies {
                // Image picker actual (rememberLauncherForActivityResult).
                implementation("androidx.activity:activity-compose:1.9.0")
            }
        }
        iosMain {
            // srcDir, not setSrcDirs — see commonMain's comment above.
            kotlin.srcDir("../../platform/ios/composeApp/iosMain/kotlin")
            dependencies {
                // Coil has no built-in network fetcher; on iOS it rides the Ktor 2 Darwin
                // engine :common already uses (Android keeps its OkHttp fetcher in :app).
                implementation("io.coil-kt.coil3:coil-network-ktor2:3.5.0")
            }
        }
        commonTest {
            // srcDir, not setSrcDirs — see commonMain's comment above.
            kotlin.srcDir("commonTest/kotlin")
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
            }
        }
        androidUnitTest {
            kotlin.srcDir("../../platform/android/composeApp/androidUnitTest/kotlin")
            dependencies {
                implementation("junit:junit:4.13.2")
                implementation("org.robolectric:robolectric:4.16")
                // androidx version matching the Compose version CMP 1.11.1 pins.
                implementation("androidx.compose.ui:ui-test-junit4:1.11.2")
            }
        }
        iosTest {
            // No custom dependencies (inherits commonTest's), but still needs the srcDir
            // override — without one, Kotlin's default convention would look for iosTest
            // sources back under this module's own tree and silently find none.
            kotlin.srcDir("../../platform/ios/composeApp/iosTest/kotlin")
        }
    }
}

dependencies {
    // Registers the test ComponentActivity that createAndroidComposeRule launches.
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.11.2")
}

android {
    namespace = "com.myhobbyislearning.fibersocial.composeapp"
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
    packageOfResClass = "com.myhobbyislearning.fibersocial.composeapp.resources"
    // Compose's resource-file discovery is hardcoded to <projectDir>/src/<sourceSet>/
    // composeResources — it isn't derived from kotlin.srcDirs at all, so the srcDir
    // overrides above (which fixed this for regular Kotlin sources) don't help here.
    // customDirectory is the plugin's own escape hatch for a relocated source set.
    customDirectory(
        sourceSetName = "commonMain",
        directoryProvider = layout.dir(provider { file("commonMain/composeResources") }),
    )
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
