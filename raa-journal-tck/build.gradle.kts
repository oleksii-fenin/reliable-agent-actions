plugins {
    id("raa.java-library")
}

description = "Conformance test suite for implementations of the reliable-agent-actions storage contract."

dependencies {
    api(project(":raa-storage-api"))
    implementation(project(":raa-core"))
}
