plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.mediahub.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mediahub.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-skeleton"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:database"))
    implementation(project(":core:security"))
    implementation(project(":core:logging"))
    implementation(libs.room.runtime)

    implementation(project(":player:engine"))
    implementation(project(":player:compatibility"))
    implementation(project(":player:mpv"))

    implementation(project(":provider:api"))
    implementation(project(":provider:base"))
    implementation(project(":provider:emby"))
    implementation(project(":provider:jellyfin"))
    implementation(project(":provider:webdav"))
    implementation(project(":provider:local"))

    implementation(project(":feature:home"))
    implementation(project(":feature:server"))
    implementation(project(":feature:library"))
    implementation(project(":feature:detail"))
    implementation(project(":feature:search"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:player"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)

    testImplementation(libs.okhttp.mockwebserver)
}
