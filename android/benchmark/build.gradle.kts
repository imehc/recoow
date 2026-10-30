plugins { alias(libs.plugins.android.test) }
android {
    namespace = "imehc.recoow.benchmark.tests"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    defaultConfig {
        minSdk = 28
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    buildTypes.create("benchmark") { matchingFallbacks += "release"; isDebuggable = true; signingConfig = signingConfigs.getByName("debug") }
    buildTypes.create("baseline") { initWith(buildTypes.getByName("benchmark")) }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
androidComponents { beforeVariants(selector().all()) { it.enable = it.buildType in setOf("benchmark", "baseline") } }
dependencies {
    implementation(libs.benchmark.macro)
    implementation(libs.uiautomator)
    implementation(libs.androidx.test)
    implementation(libs.test.runner)
}
