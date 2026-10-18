plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.vytros.projects.sharpr"
    compileSdk {
        version = release(37)
    }
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.vytros.projects.sharpr"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // ncnn is vendored for these ABIs (see scripts/setup-native.ps1)
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
                cppFlags += listOf("-O3", "-fvisibility=hidden")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
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
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    androidResources {
        // ncnn reads models straight out of the APK; keep them uncompressed so they can be mmapped quickly.
        noCompress += listOf("bin", "param")
    }
    packaging {
        jniLibs {
            pickFirsts += listOf("**/libc++_shared.so")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.exifinterface)
    implementation(libs.opencv)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
