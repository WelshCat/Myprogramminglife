plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.welshcat.tvremote"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.welshcat.tvremote"
        minSdk = 26          // Android 8.0 이상
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // 직접 설치용: 디버그 키로 서명 (스토어 배포 시 자체 키로 교체)
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        abortOnError = false   // 경고가 있어도 APK는 만들고, 리포트는 Actions 아티팩트로 확인
    }
}

// 외부 라이브러리 없음: 안드로이드 프레임워크 + Kotlin 표준 라이브러리만 사용
