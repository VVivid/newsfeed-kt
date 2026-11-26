import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.vvivid.newsfeed"
    // When changing these SDK levels, update the SDK packages that .github/workflows/android.yml
    // installs and the build requirements in README.md to match.
    compileSdk = 36
    // Use the installed SDK 36 build tools instead of downloading the plugin's older default.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.vvivid.newsfeed"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // The scheme of the sign-in redirect URI (NativeClient.REDIRECT_URI in auth/NativeClient.kt).
        // AppAuth's redirect activity answers it; the identity provider must register the full URI.
        manifestPlaceholders["appAuthRedirectScheme"] = "dev.vvivid.newsfeed"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // No signing config here: release signing material stays outside the repository.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // Settings shows BuildConfig.VERSION_NAME.
        buildConfig = true
    }

    testOptions {
        // Robolectric UI tests read the app's strings, fonts and theme.
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        // These checks compare pinned versions with whatever was published most recently (or, for
        // OldTargetApi, the newest platform installed in the SDK), so as errors an upstream release
        // would fail an unchanged build. They stay in the lint report as information (owner
        // decisions, 2026-11-25); upgrades are deliberate changes to gradle/libs.versions.toml and
        // the SDK levels in this file.
        informational +=
            setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "OldTargetApi")
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.browser)
    implementation(libs.appauth)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.commonmark)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Declares the empty activity that Compose UI tests host their content in (debug builds only).
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(composeBom)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.okhttp.mockwebserver)
}
