plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":protocol"))
    implementation(libs.slf4j.simple)
    // Dev bench: decode pairing codes out of app screenshots (parse-qr).
    implementation(libs.zxing.core)

    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

application {
    mainClass.set("app.flit.share.harness.HarnessKt")
    // The module stays the rig; the shipped desktop client is flit-cli — same
    // code, a bin name that doesn't wear test-bench vocabulary. The identity
    // home is hardcoded (~/.config/flit/flit-harness), so fingerprints survive
    // the rename and every peer that trusted this machine keeps trusting it.
    applicationName = "flit-cli"
    // Keep in step with the app's versionName (app/build.gradle.kts).
    version = "0.3.0"
    applicationDefaultJvmArgs = listOf("-Dorg.slf4j.simpleLogger.defaultLogLevel=warn")
}
