plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.welshcat.weekalarm"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.welshcat.weekalarm"
        minSdk = 26          // Android 8.0 이상
        targetSdk = 35
        versionCode = 11
        versionName = "1.8.2"
    }

    // 직접 설치(사이드로드)용 고정 서명 키 — 업데이트를 덮어서 설치할 수 있도록 항상 같은 키로 서명한다.
    // 스토어 배포용이 아니므로 저장소에 함께 보관한다.
    signingConfigs {
        create("sideload") {
            storeFile = rootProject.file("keystore/weekalarm.jks")
            storePassword = "weekalarm-sideload"
            keyAlias = "weekalarm"
            keyPassword = "weekalarm-sideload"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("sideload")
        }
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        abortOnError = false
        checkReleaseBuilds = true
        textReport = true
        textOutput = file("stdout")   // CI 로그에서 바로 볼 수 있도록
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
