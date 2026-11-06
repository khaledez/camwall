plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.khaledez.camwall"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.khaledez.camwall"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // The installed wall is signed with this key; an APK signed with any other key cannot
    // update it (INSTALL_FAILED_UPDATE_INCOMPATIBLE) without an uninstall that wipes the
    // saved layout. So every build, on every machine, uses the keystore kept next to the
    // project. It is a plain debug key (standard "android" passwords), gitignored.
    signingConfigs {
        create("wall") {
            storeFile = rootProject.file("wall.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("wall")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("wall")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3")
    implementation("androidx.media3:media3-common:$media3")
}
