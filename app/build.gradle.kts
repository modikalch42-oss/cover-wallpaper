plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.modi.coverwallpaper"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.modi.coverwallpaper"
        minSdk = 28
        targetSdk = 34
        versionCode = 3
        versionName = "2.0"
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
    lint {
        disable += "UnsafeOptInUsageError"
    }
}

dependencies {
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-datasource:$media3")
    implementation("androidx.media3:media3-database:$media3")
}
