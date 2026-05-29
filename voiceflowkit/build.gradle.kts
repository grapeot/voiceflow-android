plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

// Maven coordinate.
// - Local consumers use a Gradle composite build (`includeBuild`) and substitute
//   `com.yage:voiceflowkit` with this project.
// - Remote consumers pull the published GitHub tag through JitPack, where the
//   group is rewritten to `com.github.grapeot` and the artifact is `voiceflow-android`
//   (the repo name), e.g. implementation("com.github.grapeot:voiceflow-android:<tag>").
group = "com.yage"
version = "0.1.0"

android {
    namespace = "com.yage.voiceflowkit"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }

    // Tell AGP to produce a publishable `release` software component. Without
    // this, `components["release"]` does not exist and maven-publish fails.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Expose a Maven publication so JitPack can build the GitHub tag into a
// consumable AAR. The `release` software component is registered by AGP's
// `publishing { singleVariant("release") }` above, but only after the Android
// plugin finishes evaluating — hence `afterEvaluate`.
afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                groupId = "com.yage"
                artifactId = "voiceflowkit"
                version = project.version.toString()
                from(components["release"])
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    // The Android platform `org.json` is stubbed (returns null/0) in JVM unit tests when
    // `isReturnDefaultValues = true`. Pull in the real implementation so the parser/URL
    // builder code under test exercises genuine JSON behavior.
    testImplementation(libs.json)
}
