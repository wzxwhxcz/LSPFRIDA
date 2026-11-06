plugins {
    // AGP 9.0+ 内置 Kotlin 支持，不再单独应用 org.jetbrains.kotlin.android
    //（与主 app 模块一致；单独应用会触发 AgpWithBuiltInKotlinAppliedCheck 失败）。
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.bail.testtarget"
    compileSdk = 37
    buildToolsVersion = "35.0.1"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.bail.testtarget"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
