plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mlbb.surecure"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.mlbb.surecure"
        minSdk = 24
        targetSdk = 37
        versionCode = 3
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Signed with the local debug key so the smaller R8 build can be installed for testing and
            // the 2026-11-21 demo. Before publishing (Google Play), replace this with a real release key
            // kept out of the repository. APKs from different machines have different debug keys, so
            // uninstall the app before installing one built elsewhere.
            signingConfig = signingConfigs.getByName("debug")
            // R8 off. With optimization limited to androidx/kotlin/kotlinx (packageScope), R8 moved a
            // subclass of a package-private kotlin.collections class into another package, and every
            // release APK crashed at launch (IllegalAccessError in androidx.startup, 2026-11-20 demo
            // build). Re-enable only for the whole app, with keep rules, after testing on a device.
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // java.time (scan history, dashboard) on Android 7.0 and 7.1: minSdk is 24, java.time is API 26.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
    }
    // One APK per CPU type instead of one carrying all of them: ONNX Runtime and ML Kit ship about
    // 31 to 53 MB of native libraries per ABI, and a phone uses one. arm64-v8a covers nearly all
    // current phones, armeabi-v7a older and Android Go ones, x86_64 the emulator; 32-bit x86 is left
    // out. App bundles (.aab) ignore this and split per device on Google Play.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.onnxruntime.android)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.barcode.scanning)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation("androidx.navigation:navigation-compose:2.10.2")

    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
}