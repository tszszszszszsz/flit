import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.flit.share"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.flit.share"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // Netty jars carry overlapping META-INF bookkeeping; drop the copies.
    packaging {
        resources.excludes += setOf(
            "META-INF/INDEX.LIST",
            "META-INF/io.netty.versions.properties",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE.md",
            "META-INF/LICENSE.txt",
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Netty ships host-native transports (epoll/kqueue) that are dead weight (and
// unrunnable .so blobs) on Android; the server runs on the JDK NIO transport.
configurations.all {
    exclude(group = "io.netty", module = "netty-transport-native-epoll")
    exclude(group = "io.netty", module = "netty-transport-native-kqueue")
    exclude(group = "io.netty", module = "netty-transport-native-unix-common")
}

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

    implementation(project(":protocol"))
}
