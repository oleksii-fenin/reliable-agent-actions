package io.github.oleksiifenin.raa.storage;

/** Why an action is in state {@link ActionState#FAILED}. */
public enum FailureBasis {
    /** The tool reported a definite error: the effect was not applied. */
    DEFINITE,
    /** An outcome lookup reported "not found". This is evidence, not proof: a paused attempt may still land. */
    LOOKUP
}
