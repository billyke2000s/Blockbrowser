plugins { id("com.android.application") }
android {
    namespace = "com.example.blockbrowser"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.blockbrowser"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        // Test-only variant for scripts/private-isolation-check.sh. Same as debug, plus (from src/uitest/):
        // plain-HTTP access to localhost for the local test server, and an adb-launchable private window.
        // Never published: releases are built from the debug variant.
        create("uitest") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
        }
    }
}
