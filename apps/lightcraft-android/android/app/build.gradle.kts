plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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

android {
    namespace = "ai.storyteller.lightcraft.android"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

tasks.named("preBuild") { dependsOn(cargoNdk) }

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
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
