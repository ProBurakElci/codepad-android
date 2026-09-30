plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.proburakelci.codepad"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.proburakelci.codepad"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The editor is plain files in assets/ - nothing here is compiled or
    // minified, so it must not be touched on the way into the APK.
    androidResources {
        noCompress += listOf("js", "css", "html")
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // androidx.webkit is what lets the editor be served from a real https
    // origin instead of file://. Web Workers refuse to start on file://, and
    // the whole point of this app is that JavaScript runs in a worker.
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.activity:activity:1.11.0")
    implementation("androidx.appcompat:appcompat:1.7.1")

    testImplementation("junit:junit:4.13.2")
}
