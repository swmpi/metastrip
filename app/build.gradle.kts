plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // Namespace and applicationId are based on the sm314.com domain.
    namespace = "com.sm314.metastrip"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sm314.metastrip"
        // 29 = Android 10. Needed so MediaStore writes need no permission.
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Public HeifWriter. android.media.HeifWriter is a hidden platform class.
    implementation("androidx.heifwriter:heifwriter:1.1.0")
    implementation("com.google.android.material:material:1.12.0")
}
