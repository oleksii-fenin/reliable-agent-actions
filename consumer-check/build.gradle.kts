plugins {
    java
}

val raaVersion = providers.gradleProperty("raaVersion")
    .orElse(provider { throw GradleException("-PraaVersion=<the staged version> is required") })
// Same default as settings.gradle.kts.
val stagingRepository = providers.gradleProperty("stagingRepository")
    .orElse(layout.projectDirectory.dir("../build/staging-deploy").asFile.path)

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

dependencies {
    // The quick start of the README: the BOM, then the modules without versions.
    implementation(platform("io.github.oleksii-fenin:raa-bom:${raaVersion.get()}"))
    implementation("io.github.oleksii-fenin:raa-core")
    implementation("io.github.oleksii-fenin:raa-postgres")
    implementation("io.github.oleksii-fenin:raa-opentelemetry")
    implementation("io.github.oleksii-fenin:raa-langchain4j")
    // The floor of the supported range, which the application brings itself (ADR-0002).
    implementation("dev.langchain4j:langchain4j:1.14.0")

    // PublishedArtifactsTest reads the staging directory; JUnit through the TCK's BOM, like the suite below.
    testImplementation(platform("io.github.oleksii-fenin:raa-bom:${raaVersion.get()}"))
    testImplementation("io.github.oleksii-fenin:raa-journal-tck")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // The staged files themselves are what the test checks, so a re-staged POM or checksum reruns it.
    inputs.dir(stagingRepository).withPropertyName("stagingRepository")
    systemProperty("raa.staging", stagingRepository.get())
    systemProperty("raa.version", raaVersion.get())
    // -Psigned: the staging directory was published with -Psign, so every file must carry a good .asc signature of
    // the key -PsigningKey=<fingerprint>, checked with the local gpg (in the keyring of -PgnupgHome=<dir> if given).
    systemProperty("raa.signed", providers.gradleProperty("signed").isPresent.toString())
    systemProperty("raa.signingKey", providers.gradleProperty("signingKey").getOrElse(""))
    providers.gradleProperty("gnupgHome").orNull?.let { environment("GNUPGHOME", it) }
}

// A third-party journal's conformance test: a source set of its own, with nothing from the main source set and no
// test framework added by Gradle, so it compiles against the TCK alone and fails if the TCK does not bring what its
// test classes need.
val journalConformance = sourceSets.create("journalConformance")

dependencies {
    "journalConformanceImplementation"(platform("io.github.oleksii-fenin:raa-bom:${raaVersion.get()}"))
    "journalConformanceImplementation"("io.github.oleksii-fenin:raa-journal-tck")
    // The journal under test, loaded by name at run time (InMemoryJournalConformanceTest).
    "journalConformanceRuntimeOnly"("io.github.oleksii-fenin:raa-testkit")
    // The TCK exposes the JUnit API only; the engine is the application's, at the TCK's JUnit version.
    "journalConformanceRuntimeOnly"("org.junit.jupiter:junit-jupiter-engine")
    "journalConformanceRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

val journalConformanceTest = tasks.register<Test>("journalConformanceTest") {
    description = "Runs the published TCK against the in-memory journal, compiled against the TCK alone."
    group = "verification"
    testClassesDirs = journalConformance.output.classesDirs
    classpath = journalConformance.runtimeClasspath
    useJUnitPlatform()
}

tasks.named("check") {
    dependsOn(journalConformanceTest)
}
