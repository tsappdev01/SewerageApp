import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// One code base for Android and iOS (iPhone and iPad). commonMain: everything both share;
// androidMain / iosMain: what each platform does its own way (camera, secure storage, location...).
// iOS builds need a Mac (Xcode): the "iOS app" workflow builds them; ios/ holds the Xcode project.
kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.okio)
            implementation(libs.ktor.client.core)
            implementation(libs.jb.lifecycle.viewmodel.compose)
            implementation(libs.jb.lifecycle.runtime.compose)
            implementation(libs.jb.navigation.compose)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.activity.compose)
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
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
            implementation(libs.okhttp)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.androidx.biometric)
            implementation(libs.androidx.fragment.ktx)
            implementation(libs.androidx.work.runtime.ktx)
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(libs.junit)
                implementation(libs.okhttp.mockwebserver)
                // Screenshots of the screens with the demo data (androidUnitTest/.../screenshots, run with -Pscreenshots).
                implementation(project.dependencies.platform(libs.androidx.compose.bom))
                implementation(libs.robolectric)
                implementation(libs.roborazzi)
                implementation(libs.roborazzi.compose)
                implementation(libs.androidx.test.ext.junit)
                implementation(libs.androidx.compose.ui.test.junit4)
            }
        }
        // End-to-end: the app on an emulator against SQL Server, the API and the gateway (.github/workflows/e2e.yml).
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(project.dependencies.platform(libs.androidx.compose.bom))
                implementation(libs.androidx.compose.ui.test.junit4)
                implementation(libs.androidx.test.ext.junit)
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.rules)
            }
        }
    }
}

// Texts and the DIP logo, shared by Android and iOS (commonMain/composeResources).
compose.resources {
    publicResClass = false
    packageOfResClass = "com.meterreading.reader.resources"
    generateResClass = always
}

android {
    namespace = "com.meterreading.reader"
    compileSdk = 36

    defaultConfig {
        // The Play Store ID: it can never change once the app is uploaded. The code's package stays
        // com.meterreading.reader (namespace above).
        applicationId = "ae.dipark.fieldservice"
        minSdk = 29
        // Google Play: new apps and updates must target the latest Android within a year of its release.
        targetSdk = 36
        // Every upload to Play needs a higher versionCode: CI passes its run number (-PversionCode=...).
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 8
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Override per build: ./gradlew assembleDebug -PapiBaseUrl=https://... -PuseFakeData=true
        buildConfigField("String", "API_BASE_URL", "\"${project.findProperty("apiBaseUrl") ?: "http://10.0.2.2:5080/"}\"")
        buildConfigField("boolean", "USE_FAKE_DATA", "${project.findProperty("useFakeData") ?: "false"}")
        // First values only; the supervisor changes them in Settings (gear, behind the supervisor PIN).
        //   -PreaderLogin=rashid@dip.ae -PdeviceLock=true -PsettingsPin=1234
        buildConfigField("String", "READER_LOGIN", "\"${project.findProperty("readerLogin") ?: ""}\"")
        buildConfigField("boolean", "DEVICE_LOCK", "${project.findProperty("deviceLock") ?: "true"}")
        buildConfigField("String", "SETTINGS_PIN", "\"${project.findProperty("settingsPin") ?: ""}\"")
    }

    // Release signing with the Play upload key, from the environment (CI secrets); never stored in the repo.
    // Without it the release build is signed with the debug key, only so it can be installed and checked.
    val uploadKey = System.getenv("MR_UPLOAD_KEYSTORE")?.takeIf { it.isNotBlank() && file(it).exists() }
    signingConfigs {
        if (uploadKey != null) create("upload") {
            storeFile = file(uploadKey)
            storePassword = System.getenv("MR_UPLOAD_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("MR_UPLOAD_KEY_ALIAS")
            keyPassword = System.getenv("MR_UPLOAD_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            // Plain http to the development API (10.0.2.2): src/androidDebug/res/xml/network_security_config.xml.
            // The development data's first reader, unless -PreaderLogin says otherwise.
            buildConfigField("String", "READER_LOGIN", "\"${project.findProperty("readerLogin") ?: "rashid@dip.example"}\"")
        }
        release {
            // Phones reach the system through the DMZ gateway (gateway/README.md).
            buildConfigField("String", "API_BASE_URL", "\"${project.findProperty("apiBaseUrl") ?: "https://zApps.dipark.com/"}\"")
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Robolectric (screenshots) needs the app's resources in unit tests.
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// The screenshot classes only make pictures; they run when asked for (-Pscreenshots), not with the tests.
tasks.withType<Test>().configureEach {
    if (project.hasProperty("screenshots")) {
        filter { includeTestsMatching("*.screenshots.*") }
        systemProperty("roborazzi.test.record", "true")
    } else {
        exclude("**/screenshots/**")
    }
}
