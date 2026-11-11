import java.util.Properties
import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 发行版签名凭据从仓库根的 keystore.properties 读（不入库，见 .gitignore）。
// 文件不存在时不配 signingConfig —— 这样别人 clone 后仍能出 unsigned 包，
// 且 assembleDebug 完全不受影响。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    // App Kotlin/R namespace 保持兼容；15 字符限制只针对最终 applicationId。
    namespace = "com.dsh.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dsh.android"
        minSdk = 26
        // 🔴 必须是 28，不能升到 29+。Android 10（API 29）起，targetSdk ≥ 29 的
        // 应用不允许 execve() 自身私有目录里的文件（W^X 限制，SELinux
        // untrusted_app 域的 app_data_file 禁止 execute_no_trans）。而 Termux 的
        // 全部包（node / bash / dpkg / python）都装在 filesDir 下的 $PREFIX 里，
        // 一升到 29 就会全线 Permission denied。Termux 官方 App 同样停在 28。
        targetSdk = 28
        // 版本身份保持既有发行值；历史记录见 docs/history/gradle-version-notes.md。
        versionCode = 115
        versionName = "0.5.0"

        // 只支持 arm64-v8a：内置的引擎资产与 Termux bootstrap 都是 arm64 架构
        ndk {
            abiFilters += "arm64-v8a"
        }

        // Web 内核是不是**随 APK 打包**的（gecko flavor 覆盖成 true）。
        // 用途只有一个但很关键：`EngineManager.ensureWebViewCompat()` 是给**设备上
        // 的系统 WebView 太旧**准备的降级（按 chrome74 重编译前端 bundle）。内置内核
        // 的版本在编译期就定了（GeckoView 140），永远不可能"过旧"，跑降级只会白白把
        // 前端换成语法受限的版本 —— 所以那一整套在 gecko 版必须整个跳过。
        buildConfigField("boolean", "BUILT_IN_WEB_ENGINE", "false")
    }

    // ══ 两个 Web 内核版本共存 ══════════════════════════════════════════
    //
    // system = 系统 WebView 内核（原有版本，行为与 0.2.6 完全一致）
    // gecko  = 内置 GeckoView 内核（不依赖设备上的系统 WebView）
    //
    // ⚠️ **代价**：一旦有 flavor，构建任务就带上 flavor 名了 ——
    //   `:app:assembleDebug` 变成 `:app:assembleSystemDebug` / `:app:assembleGeckoDebug`。
    //
    // ⚠️ **两个 flavor 的 applicationId 必须都恰好 15 字符**，原因见 defaultConfig
    //   里那条注释（Termux ELF 的等长原地重写）。`com.dsh.android` 与
    //   `com.dsh.webview` 都是 15 字符，对应 `/data/data/<包名>/` = 27 字节。
    //   TermuxBootstrap 从真实安装身份读取替换路径，App 提供后备 applicationId。
    flavorDimensions += "webengine"
    productFlavors {
        create("system") {
            dimension = "webengine"
            // applicationId 继承 defaultConfig，产物与原有版本逐字节同源。
        }
        create("gecko") {
            dimension = "webengine"
            applicationId = "com.dsh.webview"
            // 两个包会同时存在于设备上（applicationId 不同 ⇒ 可共存），
            // 版本号带后缀才能在系统「应用信息」里分辨出装的是哪一个。
            versionNameSuffix = "-gecko"
            // 内核随 APK 分发 ⇒ 不需要也不应该跑「按 chrome74 降级前端」那一套。
            buildConfigField("boolean", "BUILT_IN_WEB_ENGINE", "true")
        }
    }

    // :web:host 使用同一 webengine 维度，按 flavor 提供内核实现与 Gecko 扩展资产。
    // 共享 WebHost/PageBridge 契约由 :web:contract 提供。

    signingConfigs {
        // ⚠️ 这把钥匙是发行版的终身身份，与 debug 签名**互相独立**。
        // 一旦发出去就不能换：换签名 = 老用户撞 INSTALL_FAILED_UPDATE_INCOMPATIBLE，
        // 只能卸载重装，而卸载会清掉 filesDir 下的整个 Termux 环境与 $DSH_HOME。
        if (keystorePropsFile.exists()) {
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
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            packaging {
                resources {
                    excludes += "/META-INF/{AL2.0,LGPL2.1}"
                }
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    lint {
        // targetSdk=28 是本项目的硬约束（原因见 defaultConfig 里的注释），而
        // lintVitalRelease 的 ExpiredTargetSdkVersion 会直接判 release 构建失败。
        // 本 App 只能侧载、不上架 Google Play，该检查在这里无意义，故只关掉这一条，
        // 其余 lint 检查保持生效。
        disable += "ExpiredTargetSdkVersion"
    }

    // 三个资产都用 assets.open() 读取，压缩与否对调用方透明，所以**不需要**
    // noCompress 白名单（加上反而会让 APK 从 ~60MB 涨到 ~108MB）。
    // 唯一要防的是 AGP 按扩展名做格式推断：它会把 .tar.gz 自动解压并改名成 .tar，
    // 于是运行期 openFd 按原名找不到。规避方式是资产名一律用中性后缀
    // （engine.bin / node-runtime.bin / bootstrap-aarch64.zip），不靠白名单。

    // Node 改为 Termux 官方构建（随 assets 分发、解到 app 私有 $PREFIX），
    // 不再往 jniLibs 塞可执行文件，因此无需 useLegacyPackaging / extractNativeLibs。

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets.named("main") {
        // 源码目录与共享符号链接只读；仅打包本轮锁定输入和生成插件。
        assets.setSrcDirs(listOf(layout.buildDirectory.dir("generated/android-assets")))
    }

    buildFeatures {
        viewBinding = true
        // App 提供运行身份与内核能力；library 不读取 App 的 BuildConfig。
        buildConfig = true
    }
}

// 插件产物只写 build/，不会回写源码资产或跨工作树符号链接。
val pluginComponentsDir = layout.buildDirectory.dir("generated/plugin-components")
val pluginCatalog = JsonSlurper().parse(rootProject.file("catalogs/plugin-components.json")) as Map<*, *>
val pluginComponents = pluginCatalog["components"] as List<Map<String, Any>>
val pluginAssetNames = pluginComponents.flatMap { listOf(it["asset"].toString(), "${it["id"]}.manifest.json") }
val generatedPluginNames = pluginComponents.filter { it["bundledIn"] == null }
    .flatMap { listOf(it["asset"].toString(), "${it["id"]}.manifest.json") }
val buildBuiltinPluginAssets by tasks.registering(Exec::class) {
    group = "build"
    description = "Build and verify builtin plugin assets without changing source assets"
    workingDir(rootProject.projectDir)
    inputs.files(rootProject.file("catalogs/plugin-components.json"), rootProject.file("tools/build-plugin-components.mjs"))
    inputs.files(rootProject.fileTree("tooling/plugins") { include("**/*.mjs") })
    inputs.files(pluginComponents.map { component ->
        rootProject.fileTree(component["source"].toString()) { exclude("**/node_modules/**") }
    })
    outputs.dir(pluginComponentsDir)
    commandLine(
        providers.environmentVariable("DSH_NODE_BINARY").getOrElse("node"),
        rootProject.file("tools/build-plugin-components.mjs").absolutePath,
        "build", "--out-dir", pluginComponentsDir.get().asFile.absolutePath
    )
}
val verifyRuntimeAssets by tasks.registering(Exec::class) {
    group = "verification"
    description = "Require and verify locked runtime inputs before packaging"
    workingDir(rootProject.projectDir)
    commandLine(providers.environmentVariable("DSH_NODE_BINARY").getOrElse("node"), "tools/check-runtime-assets.mjs")
}
val stageAndroidAssets by tasks.registering(Sync::class) {
    dependsOn(buildBuiltinPluginAssets, verifyRuntimeAssets)
    from("src/main/assets") { exclude(pluginAssetNames) }
    from(rootProject.file("runtime/scripts")) { include("*.mjs", "ui-probe.js") }
    from(rootProject.file("runtime/webview")) { include("webview-polyfill.js") }
    from(rootProject.file("contracts/bridge/protocol.json")) { rename { "bridge-protocol.json" } }
    from(pluginComponentsDir) { include(generatedPluginNames) }
    into(layout.buildDirectory.dir("generated/android-assets"))
}

tasks.named("preBuild") {
    dependsOn(stageAndroidAssets)
}

dependencies {
    implementation(project(":core:contracts"))
    implementation(project(":core:logging"))
    implementation(project(":runtime:termux"))
    implementation(project(":runtime:engine"))
    implementation(project(":platform:privilege"))
    implementation(project(":web:contract"))
    implementation(project(":web:host"))
    implementation(project(":feature:browser"))
    implementation(project(":feature:bubble"))
    testImplementation("junit:junit:4.13.2")
    implementation(project(":bridge:transport"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Activity 直接消费权限 API；AIDL 与 Provider 由 :platform:privilege 持有。
    implementation("dev.rikka.shizuku:api:13.1.5")

    // GeckoView 依赖仅由 :web:host 的 gecko 变体引入。

}
