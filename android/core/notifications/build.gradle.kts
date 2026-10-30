plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}
android {
    namespace = "imehc.recoow.core.notifications"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt(); testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:database"))
    implementation(libs.room.runtime)
    implementation(libs.hilt)
    ksp(libs.hilt.compiler)
    implementation(libs.coroutines.android)
    implementation(libs.work.runtime)
    implementation(libs.lifecycle.process)
    implementation(libs.serialization.json)
    androidTestImplementation(project(":core:data"))
    androidTestImplementation(project(":core:backup"))
    androidTestImplementation(libs.androidx.test)
    androidTestImplementation(libs.test.runner)
}
