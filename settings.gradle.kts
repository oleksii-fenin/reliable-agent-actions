rootProject.name = "reliable-agent-actions"

// The v0.1 modules (docs/DESIGN.md §4, §11); every raa-* module is published, the example is not.
include(
    "raa-bom",
    "raa-storage-api",
    "raa-core",
    "raa-postgres",
    "raa-journal-tck",
    "raa-testkit",
    "raa-langchain4j",
    "raa-opentelemetry",
    "examples:langchain4j-refund",
)
