plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val vCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
val vName = (project.findProperty("versionName") as String?) ?: "1.0.$vCode"
val keystoreFile: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "pl.openpfd"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.openpfd"
        minSdk = 26
        targetSdk = 35
        versionCode = vCode
        versionName = vName
    }

    signingConfigs {
        create("release") {
            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystoreFile != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
