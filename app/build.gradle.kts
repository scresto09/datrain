import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Keys and passwords stay out of the repository: signing.properties is next to local.properties, ignored by git.
val signing = Properties().apply {
    rootProject.file("signing.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
// Google Play gets the upload key; other stores get the app signing key itself: -PdatrainSigning=app
val signingKey = providers.gradleProperty("datrainSigning").getOrElse("upload")

android {
    namespace = "com.scresto.datrain"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.scresto.datrain"
        minSdk = 26
        targetSdk = 36
        // Written here so that F-Droid sees each version; the tag must match: 1.2.0 -> v1.2.0, code 10200.
        versionCode = 106
        versionName = "0.1.6"
        // For a local test server: -PdatrainManifestUrl=http://localhost:8765/manifest.json
        val manifestUrl = providers.gradleProperty("datrainManifestUrl")
            .getOrElse("https://scresto09.github.io/datrain/manifest.json")
        buildConfigField("String", "MANIFEST_URL", "\"$manifestUrl\"")
    }

    signingConfigs {
        signing.getProperty("$signingKey.storeFile")?.let { path ->
            create("release") {
                storeFile = rootProject.file(path)
                storePassword = signing.getProperty("$signingKey.storePassword")
                keyAlias = signing.getProperty("$signingKey.keyAlias")
                keyPassword = signing.getProperty("$signingKey.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Reproducible build: nothing that depends on the machine or the git checkout.
            vcsInfo.include = false
        }
        // To measure speeds as a user would see them: -PdatrainMeasure=true
        if (providers.gradleProperty("datrainMeasure").isPresent) {
            debug {
                isDebuggable = false
            }
        }
    }
    // Google's encrypted dependency list, refused by F-Droid.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    testOptions {
        // android.util.Log does not exist in tests on the computer.
        unitTests.isReturnDefaultValues = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
