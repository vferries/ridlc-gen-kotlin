plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// §3: the runtime contract depends on nothing but the Kotlin standard library.
kotlin {
    explicitApi()
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.reflect)
    testRuntimeOnly(libs.junit.platform.launcher)
}
