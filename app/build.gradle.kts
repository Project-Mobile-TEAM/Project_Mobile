// Khóa phụ thuộc nền tảng tại đây; mỗi module tính năng dùng chung version catalog.
plugins {
    alias(libs.plugins.android.application)
}

// Đổi URL bằng -PbackendBaseUrl=http://IP-LAN:8080/ khi chạy điện thoại thật.
val backendBaseUrl = providers.gradleProperty("backendBaseUrl").orElse("http://10.0.2.2:8080/").get()
require(backendBaseUrl.endsWith("/")) { "backendBaseUrl phải kết thúc bằng /" }

android {
    namespace = "com.utechat"
    compileSdk = 36

    // Mockito (ByteBuddy) chưa chính thức hỗ trợ JDK 25 dùng chạy Gradle; cờ này cho phép mock lớp final như SessionManager.
    testOptions.unitTests.all { it.systemProperty("net.bytebuddy.experimental", "true") }

    defaultConfig {
        applicationId = "com.utechat"
        minSdk = 26
        targetSdk = 36
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
            // Chỉ Debug có hồ sơ giả; chạy backend thật bằng: gradlew installDebug -PuseFakeData=false
            buildConfigField("boolean", "USE_FAKE_DATA", providers.gradleProperty("useFakeData").orElse("true").get())
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

    // Nạp thư mục schemas vào assets của androidTest để MigrationTestHelper đọc được 1.json và 2.json.
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")
}

dependencies {
    // Kéo để tải lại danh bạ khi kết nối được phục hồi.
    implementation(libs.swiperefreshlayout)
    // Khóa chặt androidx.core 1.17.0 và androidx.activity 1.10.1 tương thích compileSdk = 36.
    implementation(libs.core)
    implementation(libs.activity)
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
    // Phase 6: Media3 ExoPlayer, Vị trí GPS, WebRTC thoại 1-1, ML Kit dịch EN <-> VI, EXIF và Glide.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.play.services.location)
    implementation(libs.stream.webrtc.android)
    implementation(libs.mlkit.translate)
    implementation(libs.exifinterface)
    implementation(libs.glide)
    implementation(libs.glide.okhttp3.integration)
    annotationProcessor(libs.glide.compiler)
    // Test JVM cho logic và test thiết bị cho SQLite/FTS thật.
    testImplementation(libs.junit)
    testImplementation(libs.mockito)
    testImplementation(libs.arch.testing)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.room.testing)
}
