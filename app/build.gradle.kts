plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Version kommt beim Release aus dem Git-Tag (z. B. v0.1.0), siehe .github/workflows/release.yml.
val appVersionName = providers.gradleProperty("versionName").getOrElse("0.0.0-dev")
val appVersionCode = providers.gradleProperty("versionCode").getOrElse("1").toInt()

android {
    namespace = "de.reichimd.schichtplaner"
    compileSdk = 35

    defaultConfig {
        // NIE ÄNDERN: sonst lassen sich Updates nicht mehr installieren.
        applicationId = "de.reichimd.schichtplaner"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Signierschlüssel kommt nur aus GitHub Secrets (Umgebungsvariablen), nie aus dem Repo.
    val keystoreFile = System.getenv("SIGNING_KEYSTORE_FILE")
    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Erst ohne Verkleinern: Choco nutzt Reflection, R8-Regeln kommen in einem eigenen Schritt.
            isMinifyEnabled = false
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Neuere Java-APIs, die Choco und seine Bibliotheken nutzen, auch auf älteren Android-Versionen.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "**/module-info.class",
            )
        }
    }
}

// Release-Builds nur mit Schlüssel: eine unsignierte Release-APK wäre nicht installierbar
// und würde später Updates verwirren.
gradle.taskGraph.whenReady {
    val baustRelease = allTasks.any { it.project.path == ":app" && it.name.contains("Release") && it.name.startsWith("assemble") }
    if (baustRelease && System.getenv("SIGNING_KEYSTORE_FILE") == null) {
        throw GradleException("Release-Build ohne Signierschlüssel (SIGNING_KEYSTORE_FILE fehlt)")
    }
}

dependencies {
    implementation(project(":core"))

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
