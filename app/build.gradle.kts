plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "nz.org.aotearoa.guandanscore"
    compileSdk = 35

    defaultConfig {
        applicationId = "nz.org.aotearoa.guandanscore"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { viewBinding = true }
    testOptions {
        // android.util.Log / Base64 等框架方法在 JVM 单测里返回默认值而不是抛异常。
        unitTests.isReturnDefaultValues = true
        // Robolectric 需要真实资源与 android.graphics 实现（Bitmap/Canvas 往返测试）。
        unitTests.isIncludeAndroidResources = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // LiteRT 2.3.0 的 jar 内含 Kotlin 2.4 编译的元数据，本项目 Kotlin 编译器低于它；
        // 我们只使用其 Java 侧 org.tensorflow.lite.Interpreter，不引用 LiteRT 的 Kotlin 扩展。
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
    sourceSets["main"].assets.srcDir(rootProject.file("card_picture/card_png"))
    // T4.7: real-device benchmark reads the signed-off golden images as test assets.
    sourceSets["androidTest"].assets.srcDir(rootProject.file("testcases"))
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // T4.1: LiteRT 2.3.0（CPU）。版本固定，换版本必须同时重跑 verify_models 与 Kotlin 适配层测试。
    implementation("com.google.ai.edge.litert:litert:2.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
