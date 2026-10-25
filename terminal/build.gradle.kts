plugins {
    id("rikkahub.android.library")
}

android {
    namespace = "me.rerere.terminal"

    // 本机没有可用的 arm64 NDK 工具链，改用官方 APK 中提取的预编译 libtermux.so
    // 原配置：
    // externalNativeBuild {
    //     cmake {
    //         path = file("src/main/cpp/CMakeLists.txt")
    //         version = "3.22.1"
    //     }
    // }

    testOptions {
        // 上游单测会碰到 android.util.Base64 等 Android 类, 沿用上游的设置让它们返回默认值
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation(libs.junit)
}
