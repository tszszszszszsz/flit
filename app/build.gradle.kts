import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing reads keystore.properties (storeFile/storePassword/keyAlias/keyPassword).
// The file (and keystore it points at) is local-only, gitignored. Without it, release
// builds fall back to the debug key so reproducible source builds still assemble.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.inputStream()?.use { load(it) }
}

android {
    namespace = "app.flit.share"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.flit.share"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.1"
    }

    if (keystoreProps.isNotEmpty()) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (keystoreProps.isNotEmpty()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }

        }
    }

    // Netty jars carry overlapping META-INF bookkeeping; drop only that.
    // Upstream license/NOTICE text must travel with the APK: merge one copy
    // per path instead of stripping every copy (which would break packaging
    // on duplicate entries and drop redistribution terms).
    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
            )
            pickFirsts += setOf(
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Note: keep netty's native transport artifacts on the app classpath — ktor/netty
// probes them at engine start and a missing class hard-fails the probe. The
// probe itself is neutralized in FlitApp (io.netty.transport.noNative=true),
// because the bundled .so blobs are Linux/macOS ELFs, not Android.

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.activity.compose)
    // Recent material3 no longer pulls icons transitively; the core set covers
    // the dock (Send/Email/Add/List/Settings), share and refresh glyphs.
    implementation(libs.androidx.compose.icons)

    // QR pairing: pure-Java encode/decode (Apache-2.0, no Google services).
    implementation(libs.zxing.core)
    // CameraX pipeline the scanner previews/analyzes with.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(project(":protocol"))
    implementation(project(":core"))

    testImplementation(kotlin("test"))
}
