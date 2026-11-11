pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id in setOf("com.android.application", "com.android.library") && requested.version != null) {
                useModule("com.android.tools.build:gradle:${requested.version}")
            }
        }
    }
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 内置内核版（gecko flavor）用的 GeckoView 只发在 Mozilla 自己的仓库，
        // google()/mavenCentral() 都拿不到。见 app/build.gradle.kts 的 gecko flavor。
        maven {
            url = uri("https://maven.mozilla.org/maven2/")
            // 只让这个仓库服务 org.mozilla.geckoview:*，避免它的元数据参与其它依赖的解析。
            content { includeGroup("org.mozilla.geckoview") }
        }
    }
}

rootProject.name = "DSH"
include(
    ":app", ":core:contracts", ":core:logging",
    ":runtime:termux", ":runtime:engine", ":platform:privilege",
    ":web:contract", ":web:host", ":feature:browser", ":feature:bubble", ":bridge:transport",
)
