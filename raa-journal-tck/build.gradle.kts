plugins {
    id("raa.java-library")
}

description = "Conformance test suite for implementations of the reliable-agent-actions storage contract."

dependencies {
    api(project(":raa-storage-api"))
    // The suite drives the journal with the real decision table of the core.
    implementation(project(":raa-core"))
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter.api)
    implementation(libs.assertj.core)
}
