plugins {
    id("raa.java-library")
    id("raa.publishing")
}

description = "LangChain4j adapter of reliable-agent-actions: a decorating ToolExecutor and the tool wrapping."

// The adapter compiles against the floor of the supported range, so it cannot use a newer API by accident; the
// application brings its own LangChain4j (and with it Jackson). Tests run on the latest release by default and on
// the floor with -Plangchain4jVersion=1.14.0 (docs/adr/0002-framework-integration-boundary.md, compatibility).
// Jackson (jackson-core, for ArgumentsParser) comes with LangChain4j in every supported version and is not declared.
val langchain4jTestVersion = providers.gradleProperty("langchain4jVersion")
    .orElse(libs.versions.langchain4j.latest)

dependencies {
    api(project(":raa-core"))
    compileOnly(libs.langchain4j.floor)

    testImplementation(project(":raa-testkit"))
    testImplementation("dev.langchain4j:langchain4j:${langchain4jTestVersion.get()}")
}

tasks.withType<Test>().configureEach {
    systemProperty("raa.langchain4j.version", langchain4jTestVersion.get())
}
