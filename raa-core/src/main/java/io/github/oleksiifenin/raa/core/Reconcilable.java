package io.github.oleksiifenin.raa.core;

import java.util.Objects;

/**
 * The downstream can be asked whether an action was applied. Unknown outcomes are resolved by the lookup; a retry
 * follows only a "not found" after the maximum effect duration. Effectively-once under a bounded-pause assumption
 * (ADR-0001, consequences).
 */
public final class Reconcilable extends JournaledPolicy<Reconcilable> {

    Reconcilable(OutcomeLookup lookup) {
        super(Settings.defaults(Objects.requireNonNull(lookup, "lookup")));
    }

    private Reconcilable(Settings settings) {
        super(settings);
    }

    @Override
    Reconcilable withSettings(Settings newSettings) {
        return new Reconcilable(newSettings);
    }

    @Override
    public String journalName() {
        return "RECONCILABLE";
    }
}
