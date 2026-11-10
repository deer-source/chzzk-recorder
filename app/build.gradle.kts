plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.chzzkrec.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.chzzkrec.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // 빌드할 때마다 같은 서명을 쓰도록 고정 키 사용 (업데이트 설치 가능)
    signingConfigs {
        create("fixed") {
            storeFile = file("../keystore/app.jks")
            storePassword = "chzzkrec123"
            keyAlias = "chzzkrec"
            keyPassword = "chzzkrec123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
