plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.scresto.datrain"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.scresto.datrain"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // En essai : -PdatrainManifestUrl=http://localhost:8765/manifest.json
        val manifestUrl = providers.gradleProperty("datrainManifestUrl")
            .getOrElse("https://scresto09.github.io/datrain/manifest.json")
        buildConfigField("String", "MANIFEST_URL", "\"$manifestUrl\"")
    }

    buildTypes {
        // To measure speeds as a user would see them: -PdatrainMeasure=true
        if (providers.gradleProperty("datrainMeasure").isPresent) {
            debug {
                isDebuggable = false
            }
        }
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
