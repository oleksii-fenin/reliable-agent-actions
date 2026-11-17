package io.github.oleksiifenin.raa.langchain4j;

import java.util.List;
import java.util.Objects;

/**
 * What {@link JournalChatMemoryRepair#repair} did with each tool call that had no result in the memory.
 *
 * @param entries one entry per such call, in the order of the memory
 */
public record RepairReport(List<Entry> entries) {

    /** Copies the entries. */
    public RepairReport {
        entries = List.copyOf(entries);
    }

    /**
     * Returns whether every call without a result got one, so the memory can be sent to the model.
     *
     * @return {@code true} if no entry is {@link Status#PENDING} or {@link Status#UNMAPPED}
     */
    public boolean complete() {
        return entries.stream().allMatch(entry -> entry.status() == Status.ANSWERED);
    }

    /** How one call was handled. */
    public enum Status {
        /** A result message was added, with the text a repeated call would get (inspect mode). */
        ANSWERED,
        /**
         * Nothing can be said yet and nothing was added: an attempt of this action still holds a live lease, or the
         * journal is unavailable; repair again later.
         */
        PENDING,
        /**
         * The call cannot be mapped to an action (no provider tool-call id, a tool this repair does not know, or a call
         * history entry for another action after a key-strategy change); nothing was added.
         */
        UNMAPPED
    }

    /**
     * One call.
     *
     * @param toolCallId the provider's tool-call id, or {@code null} if it sent none
     * @param toolName   the tool name
     * @param status     how it was handled
     * @param text       the result text added, or the reason nothing was added
     */
    public record Entry(String toolCallId, String toolName, Status status, String text) {
        /** Validates the fields. */
        public Entry {
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(text, "text");
        }
    }
}
