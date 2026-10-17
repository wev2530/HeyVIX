plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Each GitHub build gets its own number (the same number as the release tag v1.0.N).
val runNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

// The fixed VIX signing key (restored by build.yml from the GitHub secrets).
val keystorePath: String? = System.getenv("VIX_KEYSTORE_PATH")
val keystorePassword: String? = System.getenv("VIX_KEYSTORE_PASSWORD")
val useVixKey: Boolean =
    !keystorePath.isNullOrBlank() &&
        !keystorePassword.isNullOrBlank() &&
        File(keystorePath).exists()

println(
    if (useVixKey) "VIX signing: using the fixed VIX key."
    else "VIX signing: NO fixed key found, using a random debug key."
)

android {
    namespace = "com.vix.heyvix"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vix.heyvix"
        minSdk = 26
        targetSdk = 35
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    signingConfigs {
        if (useVixKey) {
            create("vix") {
                storeFile = File(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = "vix"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (useVixKey) {
                signingConfig = signingConfigs.getByName("vix")
            }
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