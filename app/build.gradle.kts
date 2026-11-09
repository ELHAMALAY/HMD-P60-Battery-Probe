plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.hmdp60probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.hmdp60probe"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}
