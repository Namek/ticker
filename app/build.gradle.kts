plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.namekdev.moments"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.namekdev.moments"
        minSdk = 26
        targetSdk = 36
        versionCode = 19
        versionName = "1.8.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    // Audio tests read these files directly, outside Gradle's compiled test inputs.
    inputs.dir("src/main/res/raw")
}
