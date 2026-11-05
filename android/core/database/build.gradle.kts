plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "imehc.recoow.core.database"
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
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
// The Room plugin declares schema inputs/outputs and restores exports on KSP cache hits.
room { schemaDirectory(layout.buildDirectory.dir("roomSchemas").get().asFile.path) }

val generatedDatabase = layout.buildDirectory.dir("generated/database")
val repositoryPython = rootProject.layout.projectDirectory.file("../.venv/bin/python").asFile.takeIf { it.exists() }?.path ?: "python3"
val generateDatabase by tasks.registering(Exec::class) {
    val repository = rootProject.layout.projectDirectory.dir("..")
    inputs.files(repository.asFileTree.matching {
        include("openapi/openapi.yaml", "scripts/openapi_contract.py", "android/build-tools/generate_database.py", "android/core/database/schema-version.txt")
    })
    outputs.dir(generatedDatabase)
    commandLine(repositoryPython, rootProject.file("build-tools/generate_database.py"), "--output", generatedDatabase.get().asFile)
    environment("PYTHONDONTWRITEBYTECODE", "1")
}
androidComponents.onVariants { variant ->
    variant.sources.java?.addStaticSourceDirectory(generatedDatabase.get().asFile.path)
    variant.androidTest?.sources?.assets?.addStaticSourceDirectory(layout.projectDirectory.dir("migrations").asFile.path)
}
tasks.configureEach {
    if (name.startsWith("ksp")) dependsOn(generateDatabase)
}

tasks.register<Exec>("checkSchemaHistory") {
    dependsOn("kspDebugKotlin", "copyRoomSchemas")
    val version = layout.projectDirectory.file("schema-version.txt").asFile.readText().trim()
    commandLine(repositoryPython, rootProject.file("build-tools/generate_database.py"), "--room-schema",
        layout.buildDirectory.file("roomSchemas/imehc.recoow.core.database.generated.RecoowDatabase/$version.json").get().asFile,
        "--output", layout.projectDirectory.dir("migrations").asFile, "--check")
    environment("PYTHONDONTWRITEBYTECODE", "1")
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.turbine)
}
