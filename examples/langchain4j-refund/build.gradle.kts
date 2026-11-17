plugins {
    id("raa.java-library")
    application
}

description = "Runnable example: a refund agent on LangChain4j with the journal and the chat memory in PostgreSQL."

// Same switch as raa-langchain4j: the latest release by default, the floor with -Plangchain4jVersion=1.14.0.
val langchain4jVersion = providers.gradleProperty("langchain4jVersion").orElse(libs.versions.langchain4j.latest)

dependencies {
    implementation(project(":raa-core"))
    implementation(project(":raa-postgres"))
    implementation(project(":raa-langchain4j"))
    // FakeDownstream stands in for the payment API in the runnable demo.
    implementation(project(":raa-testkit"))
    implementation("dev.langchain4j:langchain4j:${langchain4jVersion.get()}")
    implementation(libs.postgresql)
    implementation(libs.testcontainers.postgresql)
}

application {
    mainClass = "io.github.oleksiifenin.raa.examples.refund.RefundDemo"
}

val postgresImage = providers.gradleProperty("postgresImage").orElse("postgres:18-alpine")
tasks.withType<Test>().configureEach {
    systemProperty("raa.postgres.image", postgresImage.get())
}

dependencies {
    testImplementation(project(":raa-testkit"))
}
