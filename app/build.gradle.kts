plugins {
  alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.mddorma"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.mddorma.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 10
        versionName = "2.8"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = false
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
}
