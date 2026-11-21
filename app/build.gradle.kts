plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.pitchline.android"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.pitchline.android"
        minSdk = 28
        targetSdk = 36
        versionCode = providers.environmentVariable("PITCHLINE_VERSION_CODE").orNull?.toInt() ?: 1
        versionName = providers.environmentVariable("PITCHLINE_VERSION").orElse("0.1.0").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += if (providers.gradleProperty("pitchlineEmulator").isPresent) {
                listOf("arm64-v8a", "x86_64")
            } else {
                listOf("arm64-v8a")
            }
        }
    }

    val signingPath = providers.environmentVariable("PITCHLINE_KEYSTORE")
    if (signingPath.isPresent) {
        signingConfigs.create("distribution") {
            storeFile = file(signingPath.get())
            storePassword = providers.environmentVariable("PITCHLINE_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("PITCHLINE_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("PITCHLINE_KEY_PASSWORD").get()
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("distribution")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            testProguardFiles("test-proguard-rules.pro")
        }
        // Same optimizer and packaging as release, signed with a disposable debug key.
        create("compact") {
            initWith(getByName("release"))
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    testBuildType = providers.gradleProperty("pitchlineTestBuildType").orElse("debug").get()

    buildFeatures { compose = true }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs.useLegacyPackaging = true
        dex.useLegacyPackaging = true
        jniLibs.excludes += listOf("lib/armeabi-v7a/**", "lib/x86/**")
        if (!providers.gradleProperty("pitchlineEmulator").isPresent) jniLibs.excludes += "lib/x86_64/**"
        resources.pickFirsts += listOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}
kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.05.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    "compactImplementation"("androidx.compose.ui:ui-test-manifest")
}
