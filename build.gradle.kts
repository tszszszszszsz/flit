plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
}

// Project-level resolution rules only; the buildscript classpath of the Kotlin
// and Android plugins is intentionally left alone.
val nettyFloor = libs.versions.netty.get()
val kotlinReflectVersion = checkNotNull(libs.kotlin.reflect.get().version) {
    "libs.versions.toml: kotlin-reflect must carry the Kotlin toolchain version"
}

allprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            when {
                // ktor 3.2.2 asks for Netty 4.2.2.Final; transitive modules can
                // land at other patch levels. One floor for the whole group.
                requested.group == "io.netty" -> {
                    useVersion(nettyFloor)
                    because("supply-chain floor: io.netty >= 4.2.18.Final")
                }
                // Avoid a reflect jar from a different Kotlin release on the
                // runtime/test classpath (stdlib and reflect must agree).
                requested.group == "org.jetbrains.kotlin" &&
                    requested.name == "kotlin-reflect" -> {
                    useVersion(kotlinReflectVersion)
                    because("kotlin-reflect matches the Kotlin toolchain")
                }
            }
        }
    }
}
