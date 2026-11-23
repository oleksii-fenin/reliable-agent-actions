plugins {
    `java-platform`
    id("raa.publishing")
}

description = "Bill of materials of reliable-agent-actions: one version for every published raa-* module."

// Every module that applies raa.publishing, except this one.
dependencies {
    constraints {
        api(project(":raa-storage-api"))
        api(project(":raa-core"))
        api(project(":raa-postgres"))
        api(project(":raa-journal-tck"))
        api(project(":raa-testkit"))
        api(project(":raa-langchain4j"))
        api(project(":raa-opentelemetry"))
    }
}
