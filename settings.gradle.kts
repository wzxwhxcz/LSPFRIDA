pluginManagement {
    repositories {
        // Maven Central 当前出口 IP 返回 403，先走可用镜像；保留官方仓库作为回退。
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://repo.huaweicloud.com/repository/maven")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 同时覆盖 Kotlin、Compose Multiplatform、Miuix 与 AndroidX 传递依赖。
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://repo.huaweicloud.com/repository/maven")
        google()
        mavenCentral()
    }
}

rootProject.name = "LSPFRIFA"

include(":app")