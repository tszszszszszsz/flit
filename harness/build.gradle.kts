import java.util.zip.ZipFile

plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":core"))
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
    version = "0.3.1"
    applicationDefaultJvmArgs = listOf("-Dorg.slf4j.simpleLogger.defaultLogLevel=warn")
}

// The application plugin already provides distZip/distTar/installDist. Feed
// those tasks a notices file generated from the resolved runtime classpath,
// so the CLI distribution carries upstream license text without a
// hand-maintained inventory (and without a new distribution plugin).
val thirdPartyNotices = tasks.register("generateThirdPartyNotices") {
    val runtimeClasspath = configurations.getByName("runtimeClasspath")
    val cliVersion = project.version.toString()
    inputs.files(runtimeClasspath)
    val output = layout.buildDirectory.file("generated/notices/THIRD-PARTY-NOTICES.txt")
    outputs.file(output)

    val licensePaths = listOf(
        "META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/LICENSE.md",
        "META-INF/LICENSE-notice.md", "META-INF/NOTICE", "META-INF/NOTICE.txt",
        "META-INF/NOTICE.md", "META-INF/AL2.0", "META-INF/LGPL2.1",
    )

    doLast {
        val text = buildString {
            appendLine("Third-party notices for flit-cli $cliVersion")
            appendLine("Generated from the resolved runtimeClasspath; text copied from dependency jars.")
            appendLine()
            runtimeClasspath.resolvedConfiguration.resolvedArtifacts
                .sortedWith(compareBy({ it.moduleVersion.id.group }, { it.moduleVersion.id.name }))
                .forEach { artifact ->
                    val id = artifact.moduleVersion.id
                    appendLine("${id.group}:${id.name}:${id.version}")
                    var included = false
                    if (artifact.file.name.endsWith(".jar")) {
                        val zip = ZipFile(artifact.file)
                        try {
                            for (path in licensePaths) {
                                val entry = zip.getEntry(path) ?: continue
                                val stream = zip.getInputStream(entry)
                                try {
                                    appendLine(stream.readBytes().toString(Charsets.UTF_8).trim())
                                } finally {
                                    stream.close()
                                }
                                included = true
                                break
                            }
                        } finally {
                            zip.close()
                        }
                    }
                    if (!included) appendLine("  (no license text in jar; see upstream artifact metadata)")
                    appendLine()
                }
        }
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText(text)
        }
    }
}

distributions {
    main {
        contents {
            from(thirdPartyNotices)
        }
    }
}
