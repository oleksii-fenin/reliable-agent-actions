rootProject.name = "reliable-agent-actions"

// Stage 1 modules (docs/DESIGN.md §4, §11). Adapters, telemetry and the BOM are added in their roadmap weeks.
include(
    "raa-storage-api",
    "raa-core",
    "raa-postgres",
    "raa-journal-tck",
    "raa-testkit",
    "raa-langchain4j",
    "raa-opentelemetry",
    "examples:langchain4j-refund",
)
