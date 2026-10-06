plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.meterreading.reader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.meterreading.reader"
        minSdk = 29
        targetSdk = 35
        versionCode = 6
        versionName = "0.4.0"

        // Override per build: ./gradlew assembleDebug -PapiBaseUrl=https://... -PuseFakeData=true
        buildConfigField("String", "API_BASE_URL", "\"${project.findProperty("apiBaseUrl") ?: "http://10.0.2.2:5080/"}\"")
        buildConfigField("boolean", "USE_FAKE_DATA", "${project.findProperty("useFakeData") ?: "false"}")
        // First values only; the supervisor changes them in Settings (gear, behind the supervisor PIN).
        //   -PreaderLogin=rashid@dip.ae -PdeviceLock=true -PsettingsPin=1234
        buildConfigField("String", "READER_LOGIN", "\"${project.findProperty("readerLogin") ?: ""}\"")
        buildConfigField("boolean", "DEVICE_LOCK", "${project.findProperty("deviceLock") ?: "true"}")
        buildConfigField("String", "SETTINGS_PIN", "\"${project.findProperty("settingsPin") ?: ""}\"")
    }

    buildTypes {
        debug {
            // The development API runs on plain http (10.0.2.2 is the computer running the emulator).
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            // The development data's first reader, unless -PreaderLogin says otherwise.
            buildConfigField("String", "READER_LOGIN", "\"${project.findProperty("readerLogin") ?: "rashid@dip.example"}\"")
        }
        release {
            // Phones reach the system through the DMZ gateway (gateway/README.md).
            buildConfigField("String", "API_BASE_URL", "\"${project.findProperty("apiBaseUrl") ?: "https://zApps.dipark.com/"}\"")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            manifestPlaceholders["usesCleartextTraffic"] = "false"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
