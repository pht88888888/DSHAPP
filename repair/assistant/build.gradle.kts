import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.isFile) keystorePropsFile.inputStream().use { load(it) }
}

android {
    // Compile the original application directly: one UI, resource tree and startup owner.
    namespace = "com.dsh.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.dsh.fixhelp"
        minSdk = 26
        targetSdk = 28
        versionCode = 11
        versionName = "0.5.3"
        ndk { abiFilters += "arm64-v8a" }
        missingDimensionStrategy("webengine", "system")
        buildConfigField("boolean", "BUILT_IN_WEB_ENGINE", "false")
        buildConfigField("boolean", "IS_REPAIR_ASSISTANT", "true")
        buildConfigField("String", "FLAVOR", "\"system\"")
    }
    signingConfigs {
        if (keystorePropsFile.isFile) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropsFile.isFile) signingConfig = signingConfigs.getByName("release")
            packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true; buildConfig = true }
    lint { disable += "ExpiredTargetSdkVersion" }
    sourceSets.named("debug") { manifest.srcFile("src/main/AndroidManifest.xml") }
    sourceSets.named("release") { manifest.srcFile("src/main/AndroidManifest.xml") }
    sourceSets.named("main") {
        manifest.srcFile(rootProject.file("app/src/main/AndroidManifest.xml"))
        java.srcDir(rootProject.file("app/src/main/java"))
        // The only overrides are application label and additive repair client resources.
        res.srcDir(rootProject.file("app/src/main/res"))
        assets.setSrcDirs(listOf(layout.buildDirectory.dir("generated/full-app-assets")))
    }
}

val stageFullAppAssets by tasks.registering(Sync::class) {
    dependsOn(":app:stageAndroidAssets")
    from(rootProject.layout.projectDirectory.dir("app/build/generated/android-assets"))
    from("src/main/assets") { include("repair-agent/**") }
    into(layout.buildDirectory.dir("generated/full-app-assets"))
}
tasks.named("preBuild") { dependsOn(stageFullAppAssets) }

dependencies {
    implementation(project(":repair:contract"))
    implementation(project(":core:contracts"))
    implementation(project(":core:logging"))
    implementation(project(":runtime:termux"))
    implementation(project(":runtime:engine"))
    implementation(project(":platform:privilege"))
    implementation(project(":web:contract"))
    implementation(project(":web:host"))
    implementation(project(":feature:browser"))
    implementation(project(":feature:bubble"))
    implementation(project(":bridge:transport"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
}
