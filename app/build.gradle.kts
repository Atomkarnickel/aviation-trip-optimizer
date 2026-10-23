plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---------------------------------------------------------------------------------------------------------------
// Zentrale Versionsquelle: Datei VERSION im Repository-Root. versionCode = MAJOR*10000 + MINOR*100 + PATCH.
// ---------------------------------------------------------------------------------------------------------------
val appVersion: String = rootProject.file("VERSION").readText().trim()
val semver = Regex("""^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$""")
val versionParts = semver.matchEntire(appVersion)?.destructured?.toList()?.map { it.toInt() }
    ?: error("VERSION '$appVersion' ist keine stabile SemVer-Version (MAJOR.MINOR.PATCH)")
require(versionParts[1] < 100 && versionParts[2] < 100) { "MINOR und PATCH müssen < 100 sein" }
val computedVersionCode = versionParts[0] * 10000 + versionParts[1] * 100 + versionParts[2]

val githubRepository = "Atomkarnickel/aviation-trip-optimizer"
val apkPrefix = "overhead-"

// Signierung ausschließlich über Umgebungsvariablen/CI-Secrets – niemals im Repository.
val keystorePath: String? = System.getenv("ANDROID_KEYSTORE_PATH")
val hasReleaseSigning = !keystorePath.isNullOrBlank() && file(keystorePath).exists() &&
    !System.getenv("ANDROID_KEYSTORE_PASSWORD").isNullOrBlank() &&
    !System.getenv("ANDROID_KEY_ALIAS").isNullOrBlank() &&
    !System.getenv("ANDROID_KEY_PASSWORD").isNullOrBlank()

android {
    namespace = "com.atomkarnickel.overhead"
    compileSdk = 35

    defaultConfig {
        // Bewusst die ID der Vorgänger-App (Aviation Trip Optimizer 1.x): Overhead installiert sich als Update darüber
        // und ersetzt sie auf dem Gerät. Gleicher Signierschlüssel, höherer versionCode (2.0.0 = 20000 > 10100).
        applicationId = "com.atomkarnickel.aviationoptimizer"
        minSdk = 26
        targetSdk = 35
        versionCode = computedVersionCode
        versionName = appVersion

        buildConfigField("String", "GITHUB_REPOSITORY", "\"$githubRepository\"")
        buildConfigField("String", "APK_PREFIX", "\"$apkPrefix\"")
        // Öffentliche Datendienste verlangen eine erreichbare Kontaktadresse im User-Agent.
        buildConfigField("String", "CONTACT_URL", "\"https://github.com/$githubRepository\"")

        // MapLibre bringt native Bibliotheken mit – nur gängige Architekturen ausliefern (Größe).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Ohne Secrets bleibt der Release-Build unsigniert; die Workflows prüfen das ausdrücklich.
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Native Kartenbibliotheken komprimiert ausliefern: APK etwa halb so groß, Android entpackt bei der Installation.
        jniLibs.useLegacyPackaging = true
    }
}

tasks.register("printVersion") {
    doLast { println("versionName=$appVersion versionCode=$computedVersionCode signing=$hasReleaseSigning") }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.work.runtime)
    implementation(libs.maplibre)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
