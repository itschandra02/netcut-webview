plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.itschandra.netcut"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.itschandra.netcut"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0-webview"
    }

    signingConfigs {
        create("release") {
            storeFile = file("netcut.keystore")
            storePassword = "netcut123"
            keyAlias = "netcut"
            keyPassword = "netcut123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
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

dependencies {
    // Pure framework WebView — no androidx needed
}
