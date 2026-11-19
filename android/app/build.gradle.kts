plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.nagashreeshyl.lumo"
    compileSdk { version = release(37) { minorApiLevel = 0 } }

    defaultConfig {
        applicationId = "com.nagashreeshyl.nive"   // Lumo was called Nive; same ID so old installs update in place
        minSdk = 29
        targetSdk = 36
        versionCode = (project.findProperty("lumoBuild") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("lumoVersion") as String?) ?: "0.0.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("release") {
            // Kept outside the repo; build_apk.sh supplies these (see docs/RELEASING.md).
            System.getenv("LUMO_KEYSTORE")?.let { storeFile = file(it) }
            storePassword = System.getenv("LUMO_KEY_PASSWORD")
            keyAlias = "nive"
            keyPassword = System.getenv("LUMO_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("LUMO_KEYSTORE") != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":innertube"))
    implementation(libs.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.okhttp)
    implementation(libs.media3.database)
    implementation(libs.datastore)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.coil)
    implementation(libs.reorderable)
    implementation(libs.coil.okhttp)
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.icons)
    implementation(libs.material3)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.navigation)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    debugImplementation(libs.compose.tooling)
    coreLibraryDesugaring(libs.desugaring)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.ext.junit)
}
