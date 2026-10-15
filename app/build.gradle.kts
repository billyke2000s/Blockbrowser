plugins { id("com.android.application") }
android {
    namespace = "com.example.blockbrowser"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.blockbrowser"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
        // Extra log lines read by the emulator tests; only the test-only "uitest" variant turns them on.
        buildConfigField("boolean", "TEST_LOGS", "false")
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        // Test-only variant for scripts/private-isolation-check.sh and scripts/browser-check.sh. Same as debug, plus
        // (from src/uitest/): plain-HTTP access to localhost for the local test server, and an adb-launchable
        // private window. Never published: releases are built from the debug variant.
        create("uitest") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "TEST_LOGS", "true")
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}
dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")  // tab switcher grid with drag-to-reorder
    implementation("androidx.webkit:webkit:1.11.0")             // dark-website support on newer WebView versions
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")               // real org.json for JVM tests (Android's is a stub there)
}
