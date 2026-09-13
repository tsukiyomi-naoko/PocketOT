plugins {
    id("com.android.application")
}

android {
    namespace = "app.weddingpa.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.weddingpa.mobile"
        minSdk = 28
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
