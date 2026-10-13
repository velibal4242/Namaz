plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Sürüm numarası CI'da hesaplanır (namaz deposu 3000+, geçici dal 2000+); hep ileri gider
val runNo = (System.getenv("VC") ?: "1").toInt()

android {
    namespace = "tr.veli.namaz"
    compileSdk = 35

    defaultConfig {
        applicationId = "tr.veli.namaz"
        minSdk = 31
        targetSdk = 35
        versionCode = runNo
        versionName = "0.1.$runNo"
    }

    signingConfigs {
        create("rel") {
            // CI'da anahtar gizli değişkenden gelir (herkese açık depoda dosya tutulmaz)
            storeFile = file(System.getenv("NAMAZ_KEYSTORE") ?: "namaz.keystore")
            storePassword = "namaz2026"
            keyAlias = "namaz"
            keyPassword = "namaz2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("rel")
        }
        debug {
            signingConfig = signingConfigs.getByName("rel")
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
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")
}
