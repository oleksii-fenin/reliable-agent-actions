plugins {
    id("raa.java-library")
    id("raa.publishing")
}

description = "Core of reliable-agent-actions: policies, action keys, the decision table, the action runner, reconciler and lease renewer. Framework-free."

dependencies {
    api(project(":raa-storage-api"))
    // Runner tests use the in-memory journal; tests only, so production core still knows no testkit.
    testImplementation(project(":raa-testkit"))
}
