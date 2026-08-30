import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing, when the (gitignored) credentials are present. Without them the
// release build falls back to the debug key so a fresh clone still compiles.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.github.nebulaanish.hush"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.nebulaanish.hush"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        // ponytail: arm64 only. Every phone worth running this on since ~2017 is arm64,
        // and each extra ABI adds ~25 MB of bundled Python. Add armeabi-v7a if an old
        // 32-bit device ever needs it.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    // yt-dlp's bundled Python has to exist as real files on disk, not zip entries.
    // (The library's README still says android:extractNativeLibs, which AGP now rejects.)
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // yt-dlp, bundled with its own Python. Chosen because it updates its extractor at
    // runtime, so YouTube breaking downloads does not mean shipping a new APK.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}
