package io.github.oleksiifenin.raa.core;

/** Thrown by a tool to state that its effect was definitely not applied (classified as definite by default). */
public final class ActionRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message why the action was rejected
     */
    public ActionRejectedException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a cause.
     *
     * @param message why the action was rejected
     * @param cause   the underlying error
     */
    public ActionRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
