plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The Play upload key: read from Gradle properties (~/.gradle/gradle.properties or -P) or the environment, never
// from a file in this repository. All four set: the release bundle is signed with it; any missing: the release
// build is unsigned (sign it later, or let a CI step do it). Names and steps: android/RELEASE.md.
fun secret(name: String): String? =
    (findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: System.getenv(name)?.takeIf { it.isNotBlank() }
val uploadStoreFile = secret("LENSBOLT_UPLOAD_STORE_FILE")
val uploadStorePassword = secret("LENSBOLT_UPLOAD_STORE_PASSWORD")
val uploadKeyAlias = secret("LENSBOLT_UPLOAD_KEY_ALIAS")
val uploadKeyPassword = secret("LENSBOLT_UPLOAD_KEY_PASSWORD")
val uploadSigning = uploadStoreFile != null && uploadStorePassword != null && uploadKeyAlias != null && uploadKeyPassword != null

android {
    namespace = "com.lensbolt.app"
    compileSdk = 36
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "com.lensbolt.app"
        minSdk = 29
        // Play: new apps and updates target API 36 (Android 16) from 31 August 2026
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    signingConfigs {
        if (uploadSigning) create("upload") {
            storeFile = file(uploadStoreFile!!)
            storePassword = uploadStorePassword
            keyAlias = uploadKeyAlias
            keyPassword = uploadKeyPassword
        }
    }
    // SUPPORT_LINKS: the support card after a transfer (Buy Me a Coffee, GitHub Sponsors). Off in the release build:
    // Play's payments policy forbids in-app links to outside payment methods (android/RELEASE.md §12); the links
    // stay on lensbolt.com and GitHub. A side-loaded debug build keeps the card.
    buildFeatures { buildConfig = true }
    buildTypes {
        debug { isMinifyEnabled = false; buildConfigField("boolean", "SUPPORT_LINKS", "true") }
        release {
            buildConfigField("boolean", "SUPPORT_LINKS", "false")
            // R8 shrinks and optimizes the Kotlin; resources nothing references go too. The JNI bridge
            // (Native) keeps its names: proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // the Rust library's symbol table goes into the bundle, so Play can symbolize native crash stacks
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
            signingConfig = if (uploadSigning) signingConfigs.getByName("upload") else null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// The decoder is the Rust core (../native, over the lensbolt-core crate), built with cargo-ndk into jniLibs before every build.
val cargoNdk by tasks.registering(Exec::class) {
    workingDir = file("../native")
    environment("ANDROID_NDK_HOME", android.ndkDirectory.absolutePath)
    commandLine("cargo", "ndk", "-t", "arm64-v8a", "-P", "29", "-o", file("src/main/jniLibs").absolutePath, "build", "--release")
}
tasks.named("preBuild") { dependsOn(cargoNdk) }

dependencies {
    val camerax = "1.5.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // the batch file list (10,000 rows recycled) and Save all into a folder the user picks
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // JVM tests of the pure screen rules (Guidance, Fmt); test classpath only, nothing ships
    testImplementation("junit:junit:4.13.2")
    // org.json on the JVM (android.jar's copy is a stub there): the page parser and the history file in tests
    testImplementation("org.json:json:20231013")
}
