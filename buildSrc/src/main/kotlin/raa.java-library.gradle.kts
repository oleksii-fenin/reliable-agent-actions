// Convention for every published raa-* module: JDK 25 toolchain, Java 17 bytecode (docs/OPEN-QUESTIONS.md OQ3),
// strict compiler warnings, JUnit 6. Tests run on JDK 25 by default; `-PtestJavaVersion=17` (or 21) runs them on
// that JDK instead, which is how CI covers the 17/21/25 matrix.
plugins {
    `java-library`
}

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
    // -options: building with JDK 25 for release 17 is intended. -processing: no annotation processors are used.
    options.compilerArgs.addAll(listOf("-Xlint:all,-options,-processing", "-Werror", "-parameters"))
}

tasks.withType<Javadoc>().configureEach {
    // javadoc fails on a package without types; skeleton modules have only package-info.java until their roadmap week.
    val documentedTypes = source.filter { it.name != "package-info.java" }
    onlyIf("module has types to document") { !documentedTypes.isEmpty }
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addBooleanOption("Xdoclint:all,-missing", true)
    }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val testJavaVersion = providers.gradleProperty("testJavaVersion").map(String::toInt).orElse(25)

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = testJavaVersion.map { JavaLanguageVersion.of(it) }
    }
}

// Module boundaries (docs/DESIGN.md §4): dependencies point only towards contracts. Storage implementations never see
// core, and the storage contract depends on nothing but the JDK. Checked on production configurations; tests may use
// anything. Part of `check`, so `./gradlew build` fails on a violation.
val allowedProjectDependencies = mapOf(
    ":raa-storage-api" to emptySet(),
    ":raa-core" to setOf(":raa-storage-api"),
    ":raa-postgres" to setOf(":raa-storage-api"),
    ":raa-journal-tck" to setOf(":raa-storage-api", ":raa-core"),
    ":raa-testkit" to setOf(":raa-storage-api", ":raa-core"),
    ":raa-langchain4j" to setOf(":raa-storage-api", ":raa-core"),
)
val modulesWithoutExternalDependencies = setOf(":raa-storage-api", ":raa-core")
val productionConfigurations = listOf("api", "implementation", "compileOnly", "compileOnlyApi", "runtimeOnly")

val modulePath = path
val declaredDependencies = provider {
    productionConfigurations.mapNotNull { configurations.findByName(it) }
        .flatMap { it.dependencies }
        .map { dependency ->
            if (dependency is ProjectDependency) "project " + dependency.path
            else "external ${dependency.group}:${dependency.name}"
        }
        .distinct()
}

val checkModuleBoundaries = tasks.register("checkModuleBoundaries") {
    description = "Fails if this module depends on a module or library its layer must not know."
    group = "verification"
    // Plain local values only: the configuration cache cannot serialize references to the script object.
    val declared = declaredDependencies
    val module = modulePath
    val allowed: Set<String>? = allowedProjectDependencies[module]
    val externalForbidden = module in modulesWithoutExternalDependencies
    doLast {
        if (allowed == null) {
            throw GradleException("$module has no entry in the module boundary table (raa.java-library)")
        }
        val violations = declared.get().filter { dependency ->
            if (dependency.startsWith("project ")) dependency.removePrefix("project ") !in allowed
            else externalForbidden
        }
        if (violations.isNotEmpty()) {
            throw GradleException("Module boundary violation in $module: $violations")
        }
    }
}

tasks.named("check") { dependsOn(checkModuleBoundaries) }
