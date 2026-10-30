plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}
android {
    namespace = "imehc.recoow"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    defaultConfig {
        applicationId = "imehc.recoow"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    val signingValues = listOf("RECOOW_KEYSTORE", "RECOOW_KEYSTORE_PASSWORD", "RECOOW_KEY_ALIAS", "RECOOW_KEY_PASSWORD")
        .map { providers.environmentVariable(it).orNull }
    require(signingValues.all { it == null } || signingValues.all { !it.isNullOrBlank() }) { "Provide all four RECOOW signing environment variables" }
    val distributionSigning = if (signingValues.all { it != null }) signingConfigs.create("distribution") {
        storeFile = file(signingValues[0]!!)
        storePassword = signingValues[1]
        keyAlias = signingValues[2]
        keyPassword = signingValues[3]
    } else null
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            signingConfig = distributionSigning
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildTypes.create("benchmark") {
        initWith(buildTypes.getByName("release"))
        applicationIdSuffix = ".benchmark"
        signingConfig = signingConfigs.getByName("debug")
        matchingFallbacks += "release"
        isDebuggable = false
    }
    buildTypes.create("baseline") {
        initWith(buildTypes.getByName("benchmark"))
        isMinifyEnabled = false
        isShrinkResources = false
        matchingFallbacks += "release"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    androidResources { generateLocaleConfig = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:sync"))
    implementation(project(":feature:media"))
    implementation(project(":feature:bills"))
    implementation(project(":feature:reminders"))
    implementation(project(":core:notifications"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:home"))
    implementation(project(":feature:statistics"))
    implementation(project(":feature:settings"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.appcompat)
    implementation(libs.lifecycle.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.navigation.compose)
    implementation(libs.hilt)
    ksp(libs.hilt.compiler)
    debugImplementation(libs.compose.tooling)
    debugImplementation(libs.compose.test.manifest)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.androidx.test)
    androidTestImplementation(libs.test.runner)
}
