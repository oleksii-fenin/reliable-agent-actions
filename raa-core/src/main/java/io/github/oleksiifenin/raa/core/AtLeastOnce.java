package io.github.oleksiifenin.raa.core;

/** No downstream cooperation, duplicates acceptable: an unknown outcome is retried. */
public final class AtLeastOnce extends JournaledPolicy<AtLeastOnce> {

    AtLeastOnce() {
        super(Settings.defaults(null));
    }

    private AtLeastOnce(Settings settings) {
        super(settings);
    }

    @Override
    AtLeastOnce withSettings(Settings newSettings) {
        return new AtLeastOnce(newSettings);
    }

    @Override
    public String journalName() {
        return "AT_LEAST_ONCE";
    }
}
