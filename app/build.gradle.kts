plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "se.r56.video"
    compileSdk = 34

    defaultConfig {
        applicationId = "se.r56.video"
        minSdk = 26
        targetSdk = 34
        // CI passes the GitHub run number so every build is newer than the previous one
        val build = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.0.$build"
    }

    signingConfigs {
        // Fixed key (personal sideloaded app) so updates can be installed over older builds.
        create("r56") {
            storeFile = file("r56-release.jks")
            storePassword = "r56video"
            keyAlias = "r56"
            keyPassword = "r56video"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("r56")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("r56")
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

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/**",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "META-INF/{AL2.0,LGPL2.1}"
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    val media3 = "1.3.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // FFmpeg audio decoders (DTS, TrueHD, ...) built by the Jellyfin project, matches Media3 1.3.1
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+1")

    implementation("androidx.core:core:1.13.1")
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
}
