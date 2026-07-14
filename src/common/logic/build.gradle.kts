import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
    id("com.android.library")
    kotlin("plugin.serialization")
    id("jacoco")
}

kotlin {
    jvm()
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Exported as an XCFramework for the Xcode project (#117). Static linking:
    // a single consumer, no dynamic-framework embedding needed.
    val xcFramework = XCFramework("FiberSocialCommon")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "FiberSocialCommon"
            isStatic = true
            xcFramework.add(this)
        }
    }

    sourceSets {
        // Issue #217: this module's projectDir is src/common/logic/ (see
        // ../../platform/android/settings.gradle.kts). Kotlin's default convention
        // would still look for sources under <projectDir>/src/<sourceSetName>/kotlin —
        // i.e. src/common/logic/src/commonMain/kotlin — reintroducing the redundant
        // "src" segment the flatten was meant to remove. commonMain/commonTest/jvmMain
        // stay under this module's own tree; androidMain/iosMain are overridden further
        // below to point out at src/platform/<platform>/common/ instead, since platform
        // actuals don't belong inside "common" (issue #217 point 2).
        commonMain {
            kotlin.setSrcDirs(listOf("commonMain/kotlin"))
            dependencies {
                implementation("com.fleeksoft.ksoup:ksoup:0.2.6")
                implementation("org.jetbrains:markdown:0.7.3")
                // api: ravelryHttpClient()/ravelryAuthRepository()/ravelryApiClient() expose
                // HttpClient in their signatures, so it must resolve on consumers' classpath.
                api("io.ktor:ktor-client-core:2.3.12")
                implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
                implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                // api: EventSummary/EventDetail expose kotlinx.datetime.LocalDateTime to consumers.
                // 0.7.1 to match the version Compose Multiplatform's material3 pulls in —
                // a lower pin here leaves the iOS framework link with two datetime klibs
                // whose Instant declarations collide (class in 0.6, typealias in 0.7).
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
            }
        }
        commonTest {
            kotlin.setSrcDirs(listOf("commonTest/kotlin"))
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
                implementation("io.ktor:ktor-client-mock:2.3.12")
            }
        }
        androidMain {
            // Moved out to platform/android/common/ (issue #217 point 2) — platform
            // actuals don't belong inside "common". Path relative to this module's
            // projectDir (src/common/logic/).
            kotlin.setSrcDirs(listOf("../../platform/android/common/androidMain/kotlin"))
            dependencies {
                implementation("io.ktor:ktor-client-android:2.3.12")
            }
        }
        iosMain {
            // Moved out to platform/ios/common/ (issue #217 point 2), same reasoning
            // as androidMain above.
            kotlin.setSrcDirs(listOf("../../platform/ios/common/iosMain/kotlin"))
            dependencies {
                implementation("io.ktor:ktor-client-darwin:2.3.12")
            }
        }
        jvmMain {
            kotlin.setSrcDirs(listOf("jvmMain/kotlin"))
            dependencies {
                // Only used by the jvm() target's `ravelryHttpClient()` actual, which
                // exists solely so :common:jvmTest can compile — the jvm() target isn't
                // shipped. The Android app gets the Android-engine actual below.
                implementation("io.ktor:ktor-client-cio:2.3.12")
            }
        }
    }
}

android {
    namespace = "com.myhobbyislearning.fibersocial.common"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
    }
    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Generates HTML + XML reports.
// CI compares the XML against the last successful main-branch run and fails if coverage dropped.
tasks.register<JacocoReport>("jvmCoverageReport") {
    dependsOn("jvmTest")
    executionData.setFrom(layout.buildDirectory.file("jacoco/jvmTest.exec"))
    sourceDirectories.setFrom(files("commonMain/kotlin"))
    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("classes/kotlin/jvm/main")) {
            exclude("**/*\$\$serializer.class")
        }
    )
    reports {
        html.required.set(true)
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/jvmCoverageReport/report.xml"))
    }
}
