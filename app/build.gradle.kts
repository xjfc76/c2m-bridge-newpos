plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.couchtommouth.bridge"
    compileSdk = 35

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "c2mbridge2024"
            keyAlias = "c2m-release"
            keyPassword = "c2mbridge2024"
        }
    }

    defaultConfig {
        applicationId = "com.couchtommouth.bridge"
        minSdk = 26  // Android 8.0+ (covers Android 14 & 15)
        targetSdk = 35
        versionCode = 146
        versionName = "1.4.6"

        // Shared build config (identical across flavors). POS_URL, UPDATE_URL
        // and USE_REFERENCE_AS_FOREIGN_TX_ID vary per flavor (see productFlavors).
        buildConfigField("String", "SUMUP_AFFILIATE_KEY", "\"sup_afk_UQLEOz5DtgiDiTveFpv3CAkObFE4GfoV\"")
        buildConfigField("String", "SUMUP_APP_ID", "\"CouchToMouth POS\"")
        buildConfigField("boolean", "AUTO_PRINT_CARD", "true")
        buildConfigField("boolean", "AUTO_PRINT_CASH", "false")
        buildConfigField("String", "SHOP_NAME", "\"CouchToMouth POS\"")
    }

    // Two coexisting apps from one codebase:
    //   live   -> the CURRENT shop app (unchanged: same applicationId, old POS,
    //             legacy foreignTransactionId behaviour). Build: assembleLiveDebug.
    //   newpos -> a SEPARATE app (applicationId .newpos) that installs ALONGSIDE
    //             the live one, points at the new Python POS, and sends the POS
    //             reference as the SumUp foreignTransactionId so the new backend
    //             can reconcile a lost callback. Build: assembleNewposDebug.
    flavorDimensions += "target"
    productFlavors {
        create("live") {
            dimension = "target"
            manifestPlaceholders["appLabel"] = "C2M POS"
            buildConfigField("String", "POS_URL", "\"https://pos.couchtomouth.com/\"")
            buildConfigField("String", "UPDATE_URL", "\"https://pos.couchtomouth.com/couch2mouth-bridge-app/releases/version.json\"")
            buildConfigField("boolean", "USE_REFERENCE_AS_FOREIGN_TX_ID", "false")
        }
        create("newpos") {
            dimension = "target"
            applicationIdSuffix = ".newpos"
            versionNameSuffix = "-newpos"
            manifestPlaceholders["appLabel"] = "C2M POS (New)"
            buildConfigField("String", "POS_URL", "\"https://stagingpos.couchtomouth.com/\"")
            buildConfigField("String", "UPDATE_URL", "\"https://stagingpos.couchtomouth.com/couch2mouth-bridge-app/releases/version.json\"")
            buildConfigField("boolean", "USE_REFERENCE_AS_FOREIGN_TX_ID", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("release")  // Use same signing for debug too
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")

    // WebView
    implementation("androidx.webkit:webkit:1.9.0")

    // Coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Bluetooth printing - ESC/POS library
    implementation("com.github.DantSu:ESCPOS-ThermalPrinter-Android:3.3.0")

    // SumUp SDK (7.1 — blank Card Reader page fixes / Solo Lite support)
    implementation("com.sumup:merchant-sdk:7.1.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
