plugins { id("com.android.application") }

// Native development launchers isolate outputs when Windows and Linux share a checkout.
providers.gradleProperty("dashcamDevBuildDir").orNull?.let {
    layout.buildDirectory.set(file(it))
}

// build.sh passes the package version: -PdashcamVersion=0.4
val dashcamVersion = (findProperty("dashcamVersion") as String?) ?: "0.0.1"
val versionParts = dashcamVersion.split(".").map { it.toInt() } + listOf(0, 0, 0)

android {
    namespace = "dev.skramer.dashcam.player"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.skramer.dashcam.player"
        minSdk = 19
        targetSdk = 19
        versionCode = versionParts[0] * 10000 + versionParts[1] * 100 + versionParts[2]
        versionName = dashcamVersion
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildTypes {
        release {
            // Media3 and Guava don't fit one dex without shrinking, and API 19 has no native multidex.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    lint {
        // This is a locally installed app for an Android 4.4 vehicle, not Play.
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi", "UnsafeOptInUsageError")
        abortOnError = true
    }
}

dependencies {
    // 1.4.x is the last Media3 that supports Android 4.4.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
}
