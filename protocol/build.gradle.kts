import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin/JVM on purpose: the whole Victron BLE protocol (header parsing, AES-CTR,
// bit-field decoding) is testable without an Android SDK, an emulator or a watch.
//
// Java 17 bytecode so the Android modules can consume it; no `jvmToolchain` so the module
// builds with whatever JDK 17+ runs Gradle instead of insisting on one exact JDK being present.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
