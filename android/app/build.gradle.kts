import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.services)
}

// Release signing credentials live outside git (android/keystore/, gitignored).
// Absent on machines without that directory — release builds there fall back
// to unsigned, debug builds are unaffected either way.
val releaseKeystoreProperties = Properties().apply {
    val propsFile = rootProject.file("keystore/keystore.properties")
    if (propsFile.exists()) {
        propsFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = releaseKeystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.codeyantra.armorchat"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.codeyantra.armorchat"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.5.9-large-blob"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BRAND_OWNER", "\"CodeYantra Technologies\"")
        buildConfigField("String", "TRUST_LAYER", "\"Veltrix\"")

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64", "armeabi-v7a"))
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseKeystoreProperties.getProperty("storeFile"))
                storePassword = releaseKeystoreProperties.getProperty("storePassword")
                keyAlias = releaseKeystoreProperties.getProperty("keyAlias")
                keyPassword = releaseKeystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField("boolean", "TWO_PHONE_TEST", "false")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "TWO_PHONE_TEST", "true")
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

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.biometric)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    implementation(libs.tink.android)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.bouncycastle.provider)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)

    testImplementation(libs.robolectric)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Host JNI feasibility tests use the same Rust source, compiled for the host JVM.
val buildHostMessagingJni by tasks.registering(Exec::class) {
    workingDir(rootProject.file("native/armorchat-vodozemac"))
    commandLine("cargo", "build", "--locked")
}
val buildHostMlsrsJni by tasks.registering(Exec::class) {
    workingDir(rootProject.file("native/armorchat-mlsrs"))
    commandLine("cargo", "build", "--locked")
}
tasks.withType<Test>().configureEach {
    val paths = mutableListOf<String>()
    if (providers.gradleProperty("messagingJniTests").isPresent) {
        dependsOn(buildHostMessagingJni)
        paths += rootProject.file("native/armorchat-vodozemac/target/debug").absolutePath
    }
    if (providers.gradleProperty("twoPhoneTests").isPresent) {
        dependsOn(buildHostMlsrsJni)
        paths += rootProject.file("native/armorchat-mlsrs/target/debug").absolutePath
    }
    if (paths.isNotEmpty()) {
        systemProperty("java.library.path", paths.joinToString(System.getProperty("path.separator")))
    }
}
