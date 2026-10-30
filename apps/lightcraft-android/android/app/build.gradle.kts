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

// Inter (assets/fonts, OFL) is the UI font. The build copies it into generated resources so the
// repository keeps a single copy; its licence ships in the APK's assets.
val uiFonts = layout.buildDirectory.dir("generated/uiFonts/res")
val uiLicences = layout.buildDirectory.dir("generated/uiFonts/assets")
val copyUiFonts by tasks.registering(Copy::class) {
    group = "build"
    description = "Copies the Inter UI font from assets/fonts into generated resources"
    from(workspaceDir.resolve("assets/fonts")) {
        include("Inter-*.ttf")
        rename { it.lowercase().replace('-', '_') }
        into("res/font")
    }
    from(workspaceDir.resolve("assets/fonts")) {
        include("OFL-Inter.txt")
        into("assets/licences")
    }
    into(layout.buildDirectory.dir("generated/uiFonts"))
}

android {
    namespace = "ai.storyteller.lightcraft.android"
    compileSdk = 35
    // `-PndkVersion=…` builds with another installed NDK
    ndkVersion = findProperty("ndkVersion") as String? ?: "27.2.12479018"

    defaultConfig {
        applicationId = "ai.storyteller.lightcraft.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.8.0"
        ndk { abiFilters += rustAbis }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // unsigned unless the build supplies a key; debug-signed so a CI artifact installs
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    sourceSets["main"].jniLibs.srcDir(rustJniLibs)
    sourceSets["main"].res.srcDir(uiFonts)
    sourceSets["main"].assets.srcDir(uiLicences)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

tasks.named("preBuild") { dependsOn(cargoNdk, copyUiFonts) }

dependencies {
    implementation(libs.androidx.core.ktx)
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
    // the optional AI models (SAM 2.1 tap-to-select, LaMa generative fill) run on the device
    implementation(libs.onnxruntime.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
