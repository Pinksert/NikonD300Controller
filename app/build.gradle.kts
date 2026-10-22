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
        versionCode = 1
        versionName = "1.0"

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
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
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
