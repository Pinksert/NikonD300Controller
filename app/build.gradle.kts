plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.nikond300controller"
    compileSdk = 36

    defaultConfig {
        // 与手机上已装的旧版区分开：Android 靠 applicationId 判定是否同一个 App，
        // 只改显示名称的话安装会直接覆盖旧版。namespace 仍在下面保持原包名，源码无需改动。
        applicationId = "com.example.nikond300controller2"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // NEF → JPG 真转档要解 RAW，走 LibRaw（源码在 src/main/cpp/libraw）
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++14"
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            // ★ 开 R8：关着的时候 classes*.dex 合计 10.5 MB，占未压缩体积的 61%，
            //   是整个 APK 的大头。开起来顺带把没用到的资源一起裁掉。
            optimization {
                enable = true
            }
            // nef_jni.cpp 走的是静态注册（Java_com_example_nikond300controller_NefDecoder_*），
            // 类名和方法名必须原样保留，否则装上去一解 NEF 就 UnsatisfiedLinkError。
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // 纯 JVM 单元测试（app/src/test）里会碰到源码中的 android.util.Log 调用。
    // 默认行为是直接抛 "Method i in android.util.Log not mocked" —— 这些日志跟被测逻辑无关，
    // 开 returnDefaultValues 让它们变成空操作即可，不必为了可测去改运行时代码。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    // 相册列表 (GalleryActivity) 仍使用 RecyclerView，MainActivity 用到 lifecycleScope；
    // NEF 解码版的 build.gradle 曾移除这两项，这里显式保留以确保可编译。
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
