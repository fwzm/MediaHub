plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mediahub.player.mpv"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        // 初期只打 arm64-v8a（真机均为 ARM64）
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:logging"))
    implementation(project(":core:network"))
    implementation(project(":player:engine"))

    implementation(libs.media3.common)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // 受控预编译 libmpv（jarnedemeulemeester/libmpv-android v1.0.0，见 native-lock.json）
    implementation(libs.libmpv.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
