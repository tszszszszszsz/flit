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
}

application {
    mainClass.set("app.flit.share.harness.HarnessKt")
    applicationDefaultJvmArgs = listOf("-Dorg.slf4j.simpleLogger.defaultLogLevel=warn")
}
