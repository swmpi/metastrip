plugins {
    id("com.android.application")
}

android {
    // Namespace and applicationId are based on the sm314.com domain.
    namespace = "com.sm314.metastrip"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sm314.metastrip"
        // 29 = Android 10. Needed so MediaStore writes need no permission.
        minSdk = 29
        // Stays at 36 until the Android 17 behavior changes are tested on a
        // real device. compileSdk 37 only affects what compiles.
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
    buildFeatures { viewBinding = true }

    kotlin {
        compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    // Public HeifWriter. android.media.HeifWriter is a hidden platform class.
    implementation("androidx.heifwriter:heifwriter:1.1.0")
    implementation("com.google.android.material:material:1.14.0")
}
