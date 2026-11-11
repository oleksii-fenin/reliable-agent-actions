package io.github.oleksiifenin.raa.core;

import io.github.oleksiifenin.raa.storage.FailureKind;

/** Decides whether an exception from a tool means "definitely not applied" or "outcome unknown". */
@FunctionalInterface
public interface FailureClassifier {

    /**
     * Classifies an error of the effect.
     *
     * @param error         the exception thrown by the tool
     * @param effectStarted whether the tool passed its side-effect marker before the error
     * @return the kind of failure
     */
    FailureKind classify(Throwable error, boolean effectStarted);

    /**
     * Returns the default classifier: {@link FailureKind#DEFINITE} if the effect had not started or the error is an
     * {@link ActionRejectedException}, otherwise {@link FailureKind#INDETERMINATE}.
     *
     * @return the default classifier
     */
    static FailureClassifier standard() {
        return (error, effectStarted) -> !effectStarted || error instanceof ActionRejectedException
                ? FailureKind.DEFINITE
                : FailureKind.INDETERMINATE;
    }
}
