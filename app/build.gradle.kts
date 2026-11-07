plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kyo.lanterna"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kyo.lanterna"
        minSdk = 25
        targetSdk = 34
        // Numero di build di GitHub Actions: versione crescente e visibile nell'app
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()
        versionCode = 100 + build
        versionName = "0.1.$build"
    }

    // Chiave fissa: ogni build (PC o GitHub) ha la stessa firma,
    // quindi gli aggiornamenti si installano sopra senza disinstallare.
    signingConfigs {
        create("lanterna") {
            storeFile = rootProject.file("keystore/lanterna.jks")
            storePassword = "lanterna"
            keyAlias = "lanterna"
            keyPassword = "lanterna"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("lanterna")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("lanterna")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.md",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        disable += "UnsafeOptInUsageError"
    }
}

dependencies {
    val media3 = "1.6.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.hierynomus:smbj:0.13.0")
    testImplementation("junit:junit:4.13.2")
}
