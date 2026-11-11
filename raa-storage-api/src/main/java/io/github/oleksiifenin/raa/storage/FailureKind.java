package io.github.oleksiifenin.raa.storage;

/** How certain a failed attempt is about its effect. */
public enum FailureKind {
    /** The effect was definitely not applied (for example a validation error before the side effect). */
    DEFINITE,
    /** The effect may or may not have been applied (for example a timeout after the request was sent). */
    INDETERMINATE
}
