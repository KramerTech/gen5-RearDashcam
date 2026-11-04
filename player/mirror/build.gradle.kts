plugins { id("com.android.application") }

val dashcamVersion = (findProperty("dashcamVersion") as String?) ?: "0.0"
val versionParts = dashcamVersion.split(".").map { it.toInt() } + listOf(0, 0, 0)
android {
    namespace = "dev.skramer.dashcam.mirror"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.skramer.dashcam.mirror"
        minSdk = 19
        targetSdk = 19
        versionCode = versionParts[0] * 10000 + versionParts[1] * 100 + versionParts[2]
        versionName = dashcamVersion
    }
    sourceSets.getByName("main").java.srcDir("../view/src/main/java")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    lint {
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
        abortOnError = true
    }
}
