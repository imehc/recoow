plugins { alias(libs.plugins.android.library); alias(libs.plugins.serialization) }
android {
    namespace = "imehc.recoow.core.network"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    api(libs.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}

val generateOpenAPI by tasks.registering(Exec::class) {
    val repository = rootProject.layout.projectDirectory.dir("..")
    workingDir(repository.asFile)
    inputs.file(repository.file("openapi/openapi.yaml"))
    inputs.file(repository.file("Makefile"))
    inputs.dir(repository.dir("android/build-tools/openapi-templates"))
    outputs.dir(layout.buildDirectory.dir("generated/openapi"))
    commandLine("make", "generate-android-api")
}
androidComponents.onVariants { variant ->
    variant.sources.java?.addStaticSourceDirectory(layout.buildDirectory.dir("generated/openapi/src/main/kotlin").get().asFile.path)
}
tasks.named("preBuild").configure { dependsOn(generateOpenAPI) }
