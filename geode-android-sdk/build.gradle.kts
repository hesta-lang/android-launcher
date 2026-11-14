plugins {
    id("com.android.library")
}

android {
    namespace = "com.geode.android.sdk"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.21.0+"
        }
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
}
