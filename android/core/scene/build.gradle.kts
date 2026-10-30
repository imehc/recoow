plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose)
}
android {
    namespace = "imehc.recoow.core.scene"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":core:domain"))
    implementation(libs.coroutines.android)
    androidTestImplementation(libs.androidx.test)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.coroutines.test)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.lifecycle.compose)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    debugImplementation(libs.compose.test.manifest)
    implementation(libs.filament)
    implementation(libs.filament.utils)
    implementation(libs.filament.gltfio)
}

abstract class PrepareSceneAssets : DefaultTask() {
    @get:InputFile abstract val sceneFile: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun prepare() {
        val directory = outputDirectory.get().asFile.apply { mkdirs() }
        sceneFile.get().asFile.copyTo(directory.resolve("decision-scene.glb"), overwrite = true)
    }
}
val testSceneAssets = tasks.register<PrepareSceneAssets>("prepareTestSceneAssets") {
    sceneFile.set(rootProject.layout.projectDirectory.file("../assets/decision-scene/decision-scene.glb"))
    outputDirectory.set(layout.buildDirectory.dir("generated/testSceneAssets"))
}
androidComponents.onVariants { variant ->
    variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(testSceneAssets, PrepareSceneAssets::outputDirectory)
}

abstract class PrepareDecisionAssets : DefaultTask() {
    @get:InputDirectory abstract val sourceDirectory: DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun prepare() {
        val source = sourceDirectory.get().asFile
        val target = outputDirectory.get().asFile.resolve("decision").apply { mkdirs() }
        source.listFiles()!!.filter { it.name == "asset-manifest.json" || it.name == "decision-stage.glb" || it.name.startsWith("option-") && it.extension == "glb" }
            .forEach { it.copyTo(target.resolve(it.name), overwrite = true) }
    }
}
val decisionAssets = tasks.register<PrepareDecisionAssets>("prepareDecisionAssets") {
    sourceDirectory.set(rootProject.layout.projectDirectory.dir("../assets/decision-scene"))
    outputDirectory.set(layout.buildDirectory.dir("generated/decisionAssets"))
}
androidComponents.onVariants { it.sources.assets?.addGeneratedSourceDirectory(decisionAssets, PrepareDecisionAssets::outputDirectory) }
