plugins {
    id("raa.java-library")
}

description = "PostgreSQL implementation of the reliable-agent-actions storage contract."

dependencies {
    api(project(":raa-storage-api"))
    runtimeOnly(libs.postgresql)

    testImplementation(project(":raa-journal-tck"))
    testImplementation(project(":raa-core"))
    testImplementation(libs.postgresql)
    testImplementation(libs.testcontainers.postgresql)
}

// PostgreSQL image for the tests; CI runs the floor (14) and the latest release (docs/DESIGN.md §7).
val postgresImage = providers.gradleProperty("postgresImage").orElse("postgres:18-alpine")
tasks.withType<Test>().configureEach {
    systemProperty("raa.postgres.image", postgresImage.get())
}
