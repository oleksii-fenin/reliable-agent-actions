plugins {
    id("raa.java-library")
}

description = "PostgreSQL implementation of the reliable-agent-actions storage contract."

dependencies {
    api(project(":raa-storage-api"))
}
