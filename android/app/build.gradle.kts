import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.kotlin.serialization)
}

val mockitoAgent = configurations.create("mockitoAgent")

android {
    namespace = "com.bluedrop"
    compileSdk = 36

    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localProperties.load(localPropertiesFile.inputStream())
    }

    defaultConfig {
        applicationId = "com.bluedrop"
        minSdk = 24
        targetSdk = 36
        // CI injects -PbuildVersionCode/-PbuildVersionName so every build
        // has a monotonically increasing, identifiable version
        versionCode = (project.findProperty("buildVersionCode") as String?)?.toInt() ?: 9
        versionName = (project.findProperty("buildVersionName") as String?) ?: "1.6.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    splits {
        abi {
            // the app is pure managed code today, so both APKs are identical
            // in content; keeping the split names the phone-friendly build and
            // future-proofs packaging if native libs land later
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = true
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets {
        // shared BDIP test vectors live at the monorepo root (protocol/)
        getByName("test") {
            resources.srcDir(rootProject.rootDir.parentFile.resolve("protocol"))
        }
    }

    signingConfigs {
        val storeFilePath = localProperties.getProperty("STORE_FILE")
        val ciKeystore = rootProject.file("keystores/ci.keystore")
        when {
            storeFilePath != null -> create("release") {
                storeFile = file(storeFilePath)
                storePassword = localProperties.getProperty("STORE_PASSWORD")
                keyAlias = localProperties.getProperty("KEY_ALIAS")
                keyPassword = localProperties.getProperty("KEY_PASSWORD")
            }

            ciKeystore.exists() -> create("release") {
                // convenience key for personal CI builds; see keystores/README.md
                storeFile = ciKeystore
                storePassword = "bluedrop-ci"
                keyAlias = "bluedrop-ci"
                keyPassword = "bluedrop-ci"
            }
        }
    }

    buildTypes {
        release {
            val boolean = true
            isMinifyEnabled = boolean
            isShrinkResources = boolean
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Testing
    testImplementation(libs.androidx.ui.test.junit4.android)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockito.core)
    mockitoAgent(libs.mockito.core) { isTransitive = false }
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)

    // Work manager
    implementation(libs.androidx.work.runtime.ktx)
    // Material Icons
    implementation(libs.androidx.material.icons.extended)
    // Navigation Compose
    implementation(libs.androidx.navigation.compose)
    // For AppWidgets support
    implementation(libs.androidx.glance.appwidget)
    // For interop APIs with Material 3
    implementation(libs.androidx.glance.material3)
    // For interop APIs with Material 2
    implementation(libs.androidx.glance.material)
    // Material
    implementation(libs.material)
    // Kotlinx serialization
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.profileinstaller)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.benchmark.macro.junit4)
    "baselineProfile"(project(":baselineprofile"))
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    jvmArgs("-javaagent:${mockitoAgent.asPath}")
}