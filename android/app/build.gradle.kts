import com.android.build.api.artifact.SingleArtifact
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}
val releaseVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val localVersionCode = releaseVersion.getProperty("versionCode").toInt()
val requestedVersionCode = providers.environmentVariable("RECOOW_ANDROID_VERSION_CODE").orNull
val resolvedVersionCode = requestedVersionCode?.let {
    require(it.matches(Regex("[1-9][0-9]*"))) { "RECOOW_ANDROID_VERSION_CODE must be a positive integer" }
    it.toInt()
} ?: localVersionCode
require(resolvedVersionCode in localVersionCode..2_100_000_000) { "Android build number is below baseline or exceeds platform limit" }
android {
    namespace = "imehc.recoow"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    defaultConfig {
        applicationId = "imehc.recoow"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = resolvedVersionCode
        versionName = releaseVersion.getProperty("versionName")
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
            ndk { abiFilters += "arm64-v8a" }
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
    sourceSets.getByName("baseline").kotlin.srcDir("src/benchmark/kotlin")
    // The collector must not rediscover a previously packaged application profile.
    // Only the comparable R8 benchmark and production variants consume reviewed rules.
    for (variant in listOf("release", "benchmark")) {
        sourceSets.getByName(variant).baselineProfiles.srcDir("src/profiles")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    androidResources { generateLocaleConfig = true }
    bundle { language { enableSplit = false } }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    val apkDirectory = variant.artifacts.get(SingleArtifact.APK)
    val sdkDirectory = androidComponents.sdkComponents.sdkDirectory
    val report = layout.buildDirectory.file("reports/release-artifact/${variant.name}.json")
    tasks.register<Exec>("verifyReleaseArtifact") {
        group = "verification"
        description = "Check the packaged production APK, permissions, native alignment and profiles (without a signing identity)."
        inputs.dir(apkDirectory)
        inputs.file(rootProject.file("build-tools/verify_release.py"))
        // Run the SDK validators each time, including when a local APK was signed after assembly.
        doFirst {
            commandLine("python3", rootProject.file("build-tools/verify_release.py"),
                "--apk-dir", apkDirectory.get().asFile,
                "--sdk", sdkDirectory.get().asFile,
                "--build-tools", libs.versions.buildTools.get(),
                "--min-sdk", libs.versions.minSdk.get(), "--target-sdk", libs.versions.targetSdk.get(),
                "--abi", "arm64-v8a",
                "--report", report.get().asFile)
        }
    }
}

dependencies {
    implementation(project(":core:update"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:sync"))
    implementation(project(":feature:media"))
    implementation(project(":feature:bills"))
    implementation(project(":feature:reminders"))
    implementation(project(":feature:diary"))
    implementation(project(":feature:anniversaries"))
    implementation(project(":feature:decision"))
    implementation(project(":feature:tracks"))
    implementation(project(":feature:history"))
    implementation(project(":core:location"))
    implementation(project(":feature:food"))
    implementation(project(":feature:items"))
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
