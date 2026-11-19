plugins {
    id("raa.java-library")
}

description = "OpenTelemetry spans and metrics for reliable-agent-actions (docs/DESIGN.md §10), on the OpenTelemetry API."

dependencies {
    api(project(":raa-core"))
    // The API only, no BOM forced on the application: it configures the SDK and exporters itself.
    api(libs.opentelemetry.api)

    testImplementation(project(":raa-testkit"))
    testImplementation(libs.opentelemetry.sdk.testing)
}
