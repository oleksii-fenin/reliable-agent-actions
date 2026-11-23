// Maven publication of every released raa-* module and of raa-bom (docs/DESIGN.md §4, docs/OPEN-QUESTIONS.md OQ5):
// the POM metadata Maven Central requires, the sources and javadoc jars of raa.java-library, and a staging repository.
// `./gradlew publish -Pversion=0.1.0` writes the Maven repository layout with checksums to build/staging-deploy in the
// root project. Nothing is uploaded anywhere; signing waits for the author's GPG key (OQ5).
plugins {
    `maven-publish`
}

val repositoryUrl = "https://github.com/oleksii-fenin/reliable-agent-actions"

// Each published library's module name for JPMS users is its package, so it does not change with the jar file name.
val automaticModuleNames = mapOf(
    ":raa-storage-api" to "io.github.oleksiifenin.raa.storage",
    ":raa-core" to "io.github.oleksiifenin.raa.core",
    ":raa-postgres" to "io.github.oleksiifenin.raa.postgres",
    ":raa-journal-tck" to "io.github.oleksiifenin.raa.tck",
    ":raa-testkit" to "io.github.oleksiifenin.raa.testkit",
    ":raa-langchain4j" to "io.github.oleksiifenin.raa.langchain4j",
    ":raa-opentelemetry" to "io.github.oleksiifenin.raa.opentelemetry",
)

plugins.withId("java-library") {
    val moduleName = automaticModuleNames[path]
        ?: throw GradleException("$path has no Automatic-Module-Name entry (raa.publishing)")
    tasks.named<Jar>("jar") {
        manifest {
            attributes("Automatic-Module-Name" to moduleName)
        }
    }
    publishing.publications.create<MavenPublication>("maven") {
        from(components["java"])
        // Dependencies managed by a platform (JUnit in raa-journal-tck) get the resolved version in the POM.
        versionMapping {
            allVariants {
                fromResolutionResult()
            }
        }
    }
}

plugins.withId("java-platform") {
    publishing.publications.create<MavenPublication>("maven") {
        from(components["javaPlatform"])
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name = project.name
            description = provider { project.description }
            url = repositoryUrl
            licenses {
                license {
                    name = "Apache License, Version 2.0"
                    url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    distribution = "repo"
                }
            }
            developers {
                developer {
                    id = "oleksii-fenin"
                    name = "Oleksii Fenin"
                    url = "https://github.com/oleksii-fenin"
                }
            }
            scm {
                connection = "scm:git:$repositoryUrl.git"
                developerConnection = "scm:git:ssh://git@github.com/oleksii-fenin/reliable-agent-actions.git"
                url = repositoryUrl
            }
            issueManagement {
                system = "GitHub"
                url = "$repositoryUrl/issues"
            }
        }
    }
    repositories {
        maven {
            name = "staging"
            url = uri(rootProject.layout.buildDirectory.dir("staging-deploy"))
        }
    }
}

// Maven Central accepts no -SNAPSHOT version, so a staged snapshot could never be released.
tasks.withType<PublishToMavenRepository>().configureEach {
    // A plain local value: the configuration cache cannot serialize references to the script object.
    val publishedVersion = project.version.toString()
    doFirst {
        if (publishedVersion.endsWith("-SNAPSHOT")) {
            throw GradleException("Staging needs a release version, for example -Pversion=0.1.0; got $publishedVersion")
        }
    }
}
