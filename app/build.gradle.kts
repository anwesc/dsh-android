plugins {
    id("com.android.application")
}

android {
    namespace = "dev.dsh.android"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "dev.dsh.android"
        minSdk = 26
        // targetSdk 28 is required so the app may exec binaries shipped in its own data directory.
        targetSdk = 28
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // The runtime image is already gzip-compressed; storing it avoids a second pass.
        noCompress += "gz"
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.27.1")
}
