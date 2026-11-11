package io.github.oleksiifenin.raa.core;

/** No downstream cooperation: at most one effect. An unknown outcome is escalated, never retried. */
public final class AtMostOnce extends JournaledPolicy<AtMostOnce> {

    AtMostOnce() {
        super(Settings.defaults(null));
    }

    private AtMostOnce(Settings settings) {
        super(settings);
    }

    @Override
    AtMostOnce withSettings(Settings newSettings) {
        return new AtMostOnce(newSettings);
    }

    @Override
    public String journalName() {
        return "AT_MOST_ONCE";
    }
}
