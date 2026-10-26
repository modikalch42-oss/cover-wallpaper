plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.modi.coverwallpaper"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.modi.coverwallpaper"
        minSdk = 27
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Fixed key so new builds install over the old one (personal app, private repo)
    signingConfigs {
        create("cover") {
            storeFile = file("cover.keystore")
            storePassword = "coverwall"
            keyAlias = "cover"
            keyPassword = "coverwall"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("cover")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("cover")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
