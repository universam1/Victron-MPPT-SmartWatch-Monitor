plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.android.screenshot)
}

// Release signing, if a keystore was provided (CI secrets or a local release.keystore). Both apps
// must end up with the *same* key, otherwise the Data Layer sync between them stops working — so
// this block is deliberately identical in mobile/build.gradle.kts.
val releaseKeystore = rootProject.file("release.keystore")
val releaseStorePassword: String? = System.getenv("SIGNING_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = System.getenv("SIGNING_KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("SIGNING_KEY_PASSWORD")
val hasReleaseSigning = releaseKeystore.exists() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank()

android {
    namespace = "de.universam.victron.wear"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // Both apps must share this id (and be signed with the same key) or the Wear OS Data
        // Layer keeps them in separate namespaces and the key sync silently does nothing.
        applicationId = "de.universam.victron"
        minSdk = libs.versions.minSdkWear.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // Overridden by the release workflow, which derives both from the git tag.
        versionCode = (System.getenv("VICTRON_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VICTRON_VERSION_NAME") ?: "1.0.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword ?: releaseStorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Falls back to the debug key so `assembleRelease` still produces something you can
            // sideload without any secrets configured.
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    @Suppress("UnstableApiUsage")
    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.wear)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.navigation)

    screenshotTestImplementation(libs.androidx.compose.ui.tooling)
    screenshotTestImplementation(libs.screenshot.validation.api)
}
