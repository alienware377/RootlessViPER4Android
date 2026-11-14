plugins {
    id("com.android.application")
}

// The voice isolation add-on: ONNX Runtime and the GTCRN model in an APK of
// their own, so the rootless app does not carry 30 MB of native code for a card
// most people never switch on. The app loads the code in here into its own
// process, which it only does when the add-on is signed with the app's key, so
// both builds below are signed exactly like the app. That check is only as good
// as the key: see buildSrc ReleaseSigning.kt for why releases want a private one.
//
// Written in Java on purpose: the add-on's classes live in their own class
// loader inside the app's process, and without Kotlin it carries no second
// copy of the Kotlin runtime.
android {
    namespace = "com.alienware377.viper4android.voiceaddon"
    compileSdk = AndroidConfig.compileSdk

    // No "Rootless...-rootless-..." in here: the in-app updater picks the app's
    // own file from a release by that part of the name.
    project.setProperty("archivesBaseName", "RootlessViPER4Android-voice-isolation-addon-v${AndroidConfig.versionName}")

    defaultConfig {
        // No .debug suffix for debug builds, so the app only has one name to look
        // for. While releases use the debug key too, either app build can use
        // either add-on build; with a private release key, each takes its own.
        applicationId = "com.alienware377.viper4android.voiceaddon"
        // Voice isolation only runs in the rootless app, which needs Android 10.
        minSdk = 29
        targetSdk = AndroidConfig.targetSdk
        versionCode = AndroidConfig.versionCode
        versionName = AndroidConfig.versionName

        buildConfigField("int", "API_VERSION", "${AndroidConfig.voiceAddonApi}")
        manifestPlaceholders["apiVersion"] = AndroidConfig.voiceAddonApi.toString()

        ndk {
            // ARM only. ONNX Runtime for x86 is another 40 MB, for emulators and a
            // handful of tablets; the app says the add-on does not run there.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // The app's private release key when one is set up (buildSrc ReleaseSigning.kt).
        project.releaseKey()?.let { key ->
            create("release") {
                storeFile = key.storeFile
                storePassword = key.storePassword
                keyAlias = key.keyAlias
                keyPassword = key.keyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    packaging {
        jniLibs {
            // Compressed in the APK and unpacked on install. The download is about
            // half the size, and the libraries land in the add-on's own library
            // directory, which is on the search path of the class loader the app
            // gets for this package.
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The unit tests run the real model on the desktop build of ONNX Runtime, which
// has the same Java API. The Android one only has Android libraries in it, so it
// stays out of the test runtime.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
}
