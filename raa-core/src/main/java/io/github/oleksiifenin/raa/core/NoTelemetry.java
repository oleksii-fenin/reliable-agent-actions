package io.github.oleksiifenin.raa.core;

/** The telemetry of {@link ActionTelemetry#none()}. */
final class NoTelemetry implements ActionTelemetry {

    static final NoTelemetry INSTANCE = new NoTelemetry();

    private NoTelemetry() {
    }
}
