// Load a root .env for opt-in live integration test credentials (never checked in).
// Mirrors the opencode_android_client pattern: read OPENCODE_* and push them into
// testInstrumentationRunnerArguments so the instrumented test can read them at
// runtime. When .env is absent, the args are empty and the live test self-skips
// (assumeTrue), keeping the default test run green and offline.
val envFile = rootProject.file(".env")
val env: Map<String, String> = if (envFile.exists()) {
    envFile.readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) null
            else line.substring(0, idx).trim() to line.substring(idx + 1).trim().removeSurrounding("\"")
        }
        .toMap()
} else emptyMap()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.yage.voiceflow"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yage.voiceflow"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Opt-in live e2e credentials from root .env (never committed). Empty when
        // .env is absent -> the live test self-skips via assumeTrue.
        testInstrumentationRunnerArguments["OPENCODE_BASE_URL"] = env["OPENCODE_BASE_URL"] ?: ""
        testInstrumentationRunnerArguments["OPENCODE_USERNAME"] = env["OPENCODE_USERNAME"] ?: ""
        testInstrumentationRunnerArguments["OPENCODE_PASSWORD"] = env["OPENCODE_PASSWORD"] ?: ""
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // This is a reference/sample app, not a Play Store release. We sign
            // the release build with the auto-generated debug keystore so the
            // published APK is directly installable (an unsigned APK cannot be
            // installed) without managing a private upload key. Replace with a
            // real signingConfig if this app is ever distributed through a store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":voiceflowkit"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.security.crypto)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.android)
}
