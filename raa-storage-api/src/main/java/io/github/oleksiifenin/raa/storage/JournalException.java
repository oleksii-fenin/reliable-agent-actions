package io.github.oleksiifenin.raa.storage;

/**
 * The journal could not complete an operation (database down, timeout, connection lost). Implementations throw it
 * for every storage failure, so the core can fail closed without knowing the storage technology. When it is thrown
 * by {@link ActionJournal#claim}, the claim may or may not have been committed.
 */
public class JournalException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what failed
     * @param cause   the storage error
     */
    public JournalException(String message, Throwable cause) {
        super(message, cause);
    }
}
