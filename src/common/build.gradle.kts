import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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

    sourceSets {
        commonMain.dependencies {
            // Pinned to 0.2.4: later Ksoup releases are built with Kotlin 2.2+, whose
            // metadata this project's Kotlin 2.0 compiler cannot read.
            implementation("com.fleeksoft.ksoup:ksoup:0.2.4")
            implementation("io.ktor:ktor-client-core:2.3.12")
            implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
            implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
            // api: EventSummary/EventDetail expose kotlinx.datetime.LocalDateTime to consumers.
            api("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
            implementation("io.ktor:ktor-client-mock:2.3.12")
        }
    }
}

android {
    namespace = "com.autom8ed.fibersocial.common"
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
    sourceDirectories.setFrom(files("src/commonMain/kotlin"))
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
