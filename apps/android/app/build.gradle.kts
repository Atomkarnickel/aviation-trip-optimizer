import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ---------------------------------------------------------------------------------------------------------------
// Zentrale Versionsquelle: Datei VERSION im Repository-Root. versionCode = MAJOR*10000 + MINOR*100 + PATCH.
// ---------------------------------------------------------------------------------------------------------------
val repoRoot = rootProject.projectDir.resolve("../..").canonicalFile
val appVersion: String = repoRoot.resolve("VERSION").readText().trim()
val semver = Regex("""^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$""")
val versionParts = semver.matchEntire(appVersion)?.destructured?.toList()?.map { it.toInt() }
    ?: error("VERSION '$appVersion' ist keine stabile SemVer-Version (MAJOR.MINOR.PATCH)")
require(versionParts[1] < 100 && versionParts[2] < 100) { "MINOR und PATCH müssen < 100 sein" }
val computedVersionCode = versionParts[0] * 10000 + versionParts[1] * 100 + versionParts[2]

@Suppress("UNCHECKED_CAST")
val releaseConfig = JsonSlurper().parse(repoRoot.resolve("shared/release-config.json")) as Map<String, Any>

// Signierung ausschließlich über Umgebungsvariablen/CI-Secrets – niemals im Repository.
val keystorePath: String? = System.getenv("ANDROID_KEYSTORE_PATH")
val hasReleaseSigning = !keystorePath.isNullOrBlank() && file(keystorePath).exists() &&
    !System.getenv("ANDROID_KEYSTORE_PASSWORD").isNullOrBlank() &&
    !System.getenv("ANDROID_KEY_ALIAS").isNullOrBlank() &&
    !System.getenv("ANDROID_KEY_PASSWORD").isNullOrBlank()

val apiBaseUrl: String = (project.findProperty("apiBaseUrl") as String?) ?: System.getenv("API_BASE_URL") ?: ""

android {
    namespace = "com.atomkarnickel.aviationoptimizer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.atomkarnickel.aviationoptimizer"
        minSdk = 26
        targetSdk = 35
        versionCode = computedVersionCode
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GITHUB_REPOSITORY", "\"${releaseConfig["repository"]}\"")
        buildConfigField("String", "APK_PREFIX", "\"${releaseConfig["apkAssetPrefix"]}\"")
        buildConfigField("long", "RELEASE_CACHE_SECONDS", "${releaseConfig["releaseCacheSeconds"]}L")
        buildConfigField("String", "DEFAULT_API_BASE_URL", "\"$apiBaseUrl\"")
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
            // Ohne Secrets bleibt der Release-Build unsigniert; der CI-Workflow bricht dann ausdrücklich ab.
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

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/sharedAssets"))

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { it.systemProperty("sharedDir", repoRoot.resolve("shared").absolutePath) }
    }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

// Flugzeugkatalog aus dem Backend (einzige Quelle der Wahrheit) als Asset bündeln.
val syncSharedAssets by tasks.registering(Copy::class) {
    from(repoRoot.resolve("backend/app/data/aircraft_types.json"), repoRoot.resolve("backend/app/data/airports.json"))
    into(layout.buildDirectory.dir("generated/sharedAssets"))
}
tasks.named("preBuild") { dependsOn(syncSharedAssets) }

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

tasks.register("printVersion") {
    doLast { println("versionName=$appVersion versionCode=$computedVersionCode signing=$hasReleaseSigning") }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
