plugins {
    id("raa.java-library")
}

description = "Core of reliable-agent-actions: policies, action keys, the decision table, the action runner, reconciler and lease renewer. Framework-free."

dependencies {
    api(project(":raa-storage-api"))
}
