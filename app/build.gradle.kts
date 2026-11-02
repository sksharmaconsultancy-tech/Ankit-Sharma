plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ankit.tallydata"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ankit.tallydata"
        minSdk = 26
        targetSdk = 34
        // CI build number becomes the version code so a newer APK installs over an older one.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // A fixed keystore (committed) so every build has the same signature and can be
    // installed as an update. Replace with your own private key before any public release.
    signingConfigs {
        create("shared") {
            storeFile = file("tally-viewer.keystore")
            storePassword = "android"
            keyAlias = "tallyviewer"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
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
