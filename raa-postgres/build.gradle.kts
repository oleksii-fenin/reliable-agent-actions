plugins {
    id("raa.java-library")
}

description = "PostgreSQL implementation of the reliable-agent-actions storage contract."

dependencies {
    api(project(":raa-storage-api"))
    runtimeOnly(libs.postgresql)

    testImplementation(project(":raa-journal-tck"))
    testImplementation(project(":raa-core"))
    testImplementation(project(":raa-testkit"))
    testImplementation(libs.postgresql)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.toxiproxy)
    testImplementation(libs.hikaricp)
}

// PostgreSQL image for the tests; CI runs the floor (14) and the latest release (docs/DESIGN.md §7).
val postgresImage = providers.gradleProperty("postgresImage").orElse("postgres:18-alpine")
// The randomized kill (RandomizedKillTest, nightly) runs only when randomizedKillRuns is set.
val randomizedKill = mapOf(
    "raa.randomizedKill.runs" to providers.gradleProperty("randomizedKillRuns"),
    "raa.randomizedKill.policies" to providers.gradleProperty("randomizedKillPolicies"),
    "raa.randomizedKill.seed" to providers.gradleProperty("randomizedKillSeed"),
    "raa.randomizedKill.maxDelayMillis" to providers.gradleProperty("randomizedKillMaxDelayMillis"),
)
// The overhead measurement (OverheadBenchmarkTest, Q3/Q5) runs only when overheadActions is set.
val overheadActions = providers.gradleProperty("overheadActions")
tasks.withType<Test>().configureEach {
    systemProperty("raa.postgres.image", postgresImage.get())
    randomizedKill.forEach { (name, value) -> value.orNull?.let { systemProperty(name, it) } }
    overheadActions.orNull?.let { systemProperty("raa.overhead.actions", it) }
}
