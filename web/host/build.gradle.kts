plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsh.android.web.host"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    flavorDimensions += "webengine"
    productFlavors {
        create("system") { dimension = "webengine" }
        create("gecko") { dimension = "webengine" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":web:contract"))
    implementation(project(":core:logging"))
    "geckoImplementation"("org.mozilla.geckoview:geckoview-arm64-v8a:140.0.20250707120347")
}
