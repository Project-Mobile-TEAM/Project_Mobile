// Khóa phụ thuộc nền tảng tại đây; mỗi module tính năng dùng chung version catalog.
plugins {
    alias(libs.plugins.android.application)
}

// Đổi URL bằng -PbackendBaseUrl=http://IP-LAN:8080/ khi chạy điện thoại thật.
val backendBaseUrl = providers.gradleProperty("backendBaseUrl").orElse("http://10.0.2.2:8080/").get()
require(backendBaseUrl.endsWith("/")) { "backendBaseUrl phải kết thúc bằng /" }

android {
    namespace = "com.utechat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.utechat"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // URL không chứa secret; Android chỉ gọi backend, không nhận credential Supabase.
        buildConfigField("String", "BACKEND_BASE_URL", "\"$backendBaseUrl\"")
        buildConfigField("boolean", "USE_FAKE_DATA", "false")
        // Commit schema JSON để cả nhóm thấy rõ thay đổi Room cần migration.
        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf("room.schemaLocation" to "$projectDir/schemas")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // Chỉ Debug có hồ sơ giả để phát triển UI khi backend chưa có module nghiệp vụ.
            buildConfigField("boolean", "USE_FAKE_DATA", "true")
        }
        getByName("release") {
            buildConfigField("boolean", "USE_FAKE_DATA", "false")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        // Bytecode Java17 thống nhất với backend và slide môn học.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Views/XML, Material3 và điều hướng một Activity.
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)
    // Room dùng Java annotationProcessor, không dùng Kapt/KSP.
    implementation(libs.room.runtime)
    annotationProcessor(libs.room.compiler)
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)
    implementation(libs.work.runtime)
    // Test JVM cho logic và test thiết bị cho SQLite/FTS thật.
    testImplementation(libs.junit)
    testImplementation(libs.mockito)
    testImplementation(libs.arch.testing)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.room.testing)
}
