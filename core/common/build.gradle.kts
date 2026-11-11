plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.mediahub.core.common"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // 同步契约需要 ServerType（wire 值与本端类型的对照表）。core:model 是纯 Kotlin JVM 模块、
    // 只依赖 junit，不存在环；用 api 是为了让调用方拿到 SourceSyncCandidate 里的类型时无需自己再声明依赖。
    api(project(":core:model"))
    testImplementation(libs.junit)
}
