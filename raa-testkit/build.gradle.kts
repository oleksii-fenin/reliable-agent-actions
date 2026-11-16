plugins {
    id("raa.java-library")
}

description = "Test kit for reliable-agent-actions: fault points, a fake downstream and a process kill/pause harness."

dependencies {
    api(project(":raa-core"))

    testImplementation(project(":raa-journal-tck"))
}
