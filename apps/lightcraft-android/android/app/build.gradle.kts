plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // JVM screenshots of the editor chrome (`./gradlew :app:recordPaparazziRelease`), no device needed
    alias(libs.plugins.paparazzi)
}

// The engine is Rust: `cargo ndk` builds `apps/lightcraft-android` (the JNI crate) into
// build/rustJniLibs/<abi>/liblightcraft_android.so before every APK. `-PrustAbis=arm64-v8a` limits the
// ABIs (CI and quick device builds); `-PrustProfile=dev` builds the debug engine (much slower renders).
val workspaceDir = rootDir.resolve("../../..").canonicalFile
val rustJniLibs = layout.buildDirectory.dir("rustJniLibs")
val rustAbis = (findProperty("rustAbis") as String? ?: "arm64-v8a,x86_64").split(",").map { it.trim() }.filter { it.isNotEmpty() }
val rustProfile = findProperty("rustProfile") as String? ?: "release"

val cargoNdk by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the LightCraft engine for Android with cargo-ndk"
    workingDir = workspaceDir
    val out = rustJniLibs.get().asFile
    val args = mutableListOf("cargo", "ndk", "--platform", "29", "-o", out.absolutePath)
    rustAbis.forEach { args += listOf("-t", it) }
    args += listOf("build", "-p", "lightcraft-android")
    if (rustProfile == "release") args += "--release"
    commandLine(args)
    // cargo decides what is stale; always ask it
    outputs.upToDateWhen { false }
}

// The UI font is the desktop app's Inter (assets/fonts, OFL), copied into generated resources at
// build time so the repository keeps one copy of each font file.
val interFonts by tasks.registering(Copy::class) {
    from(workspaceDir.resolve("assets/fonts")) { include("Inter-*.ttf") }
    into(layout.buildDirectory.dir("generated/fontRes/font"))
    rename { it.lowercase().replace('-', '_') }
}

android {
    namespace = "ai.storyteller.lightcraft.android"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "ai.storyteller.lightcraft.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 19
        versionName = "0.16.3"
        ndk { abiFilters += rustAbis }
    }

    // Release APKs are signed with the key named in LIGHTCRAFT_KEYSTORE (CI decodes it from the
    // ANDROID_KEYSTORE_BASE64 secret), so every build installs over the last; without one they fall
    // back to the machine's debug key. The key never lives in the repository.
    val releaseKey = System.getenv("LIGHTCRAFT_KEYSTORE")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (releaseKey != null) {
            create("lightcraft") {
                storeFile = file(releaseKey)
                storePassword = System.getenv("LIGHTCRAFT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LIGHTCRAFT_KEY_ALIAS")
                keyPassword = System.getenv("LIGHTCRAFT_KEY_PASSWORD") ?: System.getenv("LIGHTCRAFT_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseKey != null) "lightcraft" else "debug")
        }
    }
    sourceSets["main"].jniLibs.srcDir(rustJniLibs)
    sourceSets["main"].res.srcDir(layout.buildDirectory.dir("generated/fontRes"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

tasks.named("preBuild") { dependsOn(cargoNdk, interFonts) }

dependencies {
    implementation(libs.androidx.core.ktx)
    // the launch screen (the lynx on espresso) on every Android version the app supports
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    // on-device subject segmentation (opt-in AI masks and background removal), via Play services
    implementation(libs.mlkit.subject.segmentation)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
