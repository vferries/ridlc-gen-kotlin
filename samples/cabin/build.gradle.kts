plugins {
    alias(libs.plugins.kotlin.jvm)
}

// K4 adds the generated cabin package, the consumer, the provider and the
// `application` entry point.
dependencies {
    implementation(project(":ridl-rt-kt"))
    implementation(project(":ridl-rt-kt-loopback"))
}
