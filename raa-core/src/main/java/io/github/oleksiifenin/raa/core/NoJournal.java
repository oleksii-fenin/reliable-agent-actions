package io.github.oleksiifenin.raa.core;

/** Policy of a read-only tool: the call is not journaled. */
public final class NoJournal implements ActionPolicy {

    static final NoJournal INSTANCE = new NoJournal();

    private NoJournal() {
    }

    @Override
    public String toString() {
        return "NONE";
    }
}
