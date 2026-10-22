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
    // 相册列表 (GalleryActivity) 仍使用 RecyclerView，且 MainActivity 用到 lifecycle，
    // 显式保留依赖以确保可编译（master 版本移除了这两项，靠传递依赖不一定稳定）。
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
