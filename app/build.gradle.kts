plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.amiigo.harmattan"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.amiigo.harmattan"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"
    }

    buildTypes {
        // The N9 theme PNGs are used as-is from the firmware; don't let aapt re-encode them.
        debug {
            isCrunchPngs = false
        }
        release {
            isMinifyEnabled = false
            isCrunchPngs = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // CHANGELOG.md from the repo root, for the About page's release notes.
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/changelog"))
}

val copyChangelog by tasks.registering(Copy::class) {
    from(rootProject.file("CHANGELOG.md"))
    into(layout.buildDirectory.dir("generated/changelog"))
}
tasks.named("preBuild") { dependsOn(copyChangelog) }

dependencies {
    // UI is plain framework views (full control over drawing and timing). These two are only for the
    // optional Shizuku mode that mirrors the system's recent apps list.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
