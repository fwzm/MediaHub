plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.mediahub.feature.settings"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
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

// 本模块的 Robolectric 用例（backup 套件）会把 Conscrypt 插为默认 TLS provider，且该 provider
// 在 JVM 内一直存活；JDK 21 上 Conscrypt 需反射读 InetAddress 内部字段，缺 --add-opens 时
// 同模块之后的真 TLS 用例（W6-42）会在握手阶段抛 InaccessibleObjectException。
// 仅放宽测试 JVM 的模块访问，不改断言、不改生产语义；真机上 Conscrypt 是平台组件，无此问题。
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    jvmArgs("--add-opens=java.base/java.net=ALL-UNNAMED")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:common"))
    implementation(project(":core:security"))
    implementation(project(":core:ui"))
    implementation(project(":provider:api"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:logging"))
    testImplementation(project(":provider:emby"))
    testImplementation(project(":provider:jellyfin"))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
