package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.oleksiifenin.raa.storage.ActionJournal;
import io.github.oleksiifenin.raa.tck.ActionJournalTck;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * A journal's conformance test as a third party writes it: it compiles against {@code raa-journal-tck} alone (the
 * storage contract and core come with it), uses the suite's helpers, which return core types, and runs the whole
 * suite from the published jars. A third-party journal does not depend on the testkit, so the in-memory journal it
 * tests here is loaded at run time only; otherwise the testkit would put core on the compile class path and hide a
 * TCK that does not expose core.
 */
class InMemoryJournalConformanceTest extends ActionJournalTck {

    private final ActionJournal journal;
    private final Method transactionsWaitingForLock;

    InMemoryJournalConformanceTest() throws ReflectiveOperationException {
        Class<?> type = Class.forName("io.github.oleksiifenin.raa.testkit.InMemoryActionJournal");
        journal = (ActionJournal) type.getConstructor().newInstance();
        transactionsWaitingForLock = type.getMethod("transactionsWaitingForLock");
    }

    @Override
    protected ActionJournal journal() {
        return journal;
    }

    @Override
    protected void awaitTransactionsWaitingForLock(int count) {
        eventually(() -> waitingForLock() >= count);
    }

    @Test
    void theSuiteHelpers_areUsableFromATestClass() {
        assertNotNull(table());
        assertEquals(32, newKey().length);
    }

    private int waitingForLock() {
        try {
            return (int) transactionsWaitingForLock.invoke(journal);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
