import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
}

val keystoreFile = rootProject.file("keystore.properties")
val keystore = Properties().apply {
    if (keystoreFile.exists()) {
        FileInputStream(keystoreFile).use { load(it) }
    }
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
        versionCode = 20
        versionName = "1.0.3"
    }

    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystoreFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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

    lint {
        // targetSdk 28 is intentional: Android >= 29 forbids exec of app-private binaries.
        disable += "ExpiredTargetSdkVersion"
        // Catch JDK APIs the Android runtime does not ship (e.g. InputStream.readAllBytes on
        // API < 33). Compilation against the SDK does not prove runtime availability.
        error += "NewApi"
        abortOnError = true
        checkReleaseBuilds = true
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.27.1")
}
