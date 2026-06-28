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
            implementation("io.ktor:ktor-client-core:2.3.12")
            implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
            implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
            implementation("io.ktor:ktor-client-mock:2.3.12")
        }
        androidMain.dependencies {
            implementation("io.ktor:ktor-client-android:2.3.12")
            implementation("androidx.security:security-crypto:1.0.0")
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("junit:junit:4.13.2")
                implementation("org.robolectric:robolectric:4.12.2")
            }
        }
    }
}

android {
    namespace = "com.autom8ed.fibersocial.common"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
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

// Coverage is scoped to packages that have tests. Expand the include patterns as new
// packages gain test coverage.
val coveredPackages = fileTree(layout.buildDirectory.dir("classes/kotlin/jvm/main")) {
    include("**/auth/**")
    exclude("**/*\$\$serializer.class")
}

// Generates HTML + XML reports.
// CI compares the XML against the last successful main-branch run and fails if coverage dropped.
tasks.register<JacocoReport>("jvmCoverageReport") {
    dependsOn("jvmTest")
    executionData.setFrom(layout.buildDirectory.file("jacoco/jvmTest.exec"))
    sourceDirectories.setFrom(files("src/commonMain/kotlin"))
    classDirectories.setFrom(coveredPackages)
    reports {
        html.required.set(true)
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/jvmCoverageReport/report.xml"))
    }
}
