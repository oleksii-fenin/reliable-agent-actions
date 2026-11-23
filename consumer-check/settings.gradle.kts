// A separate build that uses the published artifacts the way an application does (docs/DESIGN.md §13, week 7 stage D):
// every io.github.oleksii-fenin module comes only from the staging directory that `./gradlew publish -Pversion=<v>`
// fills in the main build, never from this repository's sources or from Maven Central. CI runs it twice:
//   ./gradlew -p consumer-check check -PraaVersion=<v>            (Gradle module metadata)
//   ./gradlew -p consumer-check check -PraaVersion=<v> -PpomOnly  (the POMs only, as Maven resolves them)
rootProject.name = "raa-consumer-check"

val stagingRepository = providers.gradleProperty("stagingRepository")
    .orElse(settingsDir.resolve("../build/staging-deploy").path)
val pomOnly = providers.gradleProperty("pomOnly").isPresent

dependencyResolutionManagement {
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "staging"
                    url = uri(file(stagingRepository.get()))
                    if (pomOnly) {
                        // No artifact() fallback: a missing POM must fail resolution, not yield a jar without dependencies.
                        metadataSources {
                            mavenPom()
                        }
                    }
                }
            }
            filter {
                includeGroup("io.github.oleksii-fenin")
            }
        }
        mavenCentral()
    }
}
