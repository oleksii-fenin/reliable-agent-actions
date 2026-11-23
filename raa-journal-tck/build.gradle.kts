plugins {
    id("raa.java-library")
    id("raa.publishing")
}

description = "Conformance test suite for implementations of the reliable-agent-actions storage contract."

dependencies {
    api(project(":raa-storage-api"))
    // The suite drives the journal with the real decision table of the core, which its protected helpers return
    // (table()), so a journal's test class needs core on its compile class path.
    api(project(":raa-core"))
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter.api)
    implementation(libs.assertj.core)
}
