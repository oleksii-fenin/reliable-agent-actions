package io.github.oleksiifenin.raa.langchain4j;

import io.github.oleksiifenin.raa.core.ActionOutcome;
import io.github.oleksiifenin.raa.core.ToolCall;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The texts of docs/DESIGN.md §6, "What the model is told". */
final class StandardOutcomeRenderer implements OutcomeRenderer {

    /** The runner states the time in its reason ("… call again after 2026-11-16T10:00:00Z"). */
    private static final Pattern CALL_AGAIN_AFTER = Pattern.compile("call again after (\\S+)");
    /** A replay of a succeeded action whose result was redacted. */
    private static final String RESULT_REMOVED = "this action was already performed; its recorded result was removed"
            + " from the journal; do not repeat";

    @Override
    public String render(ActionOutcome outcome, ToolCall call) {
        if (outcome instanceof ActionOutcome.Succeeded) {
            return ((ActionOutcome.Succeeded) outcome).resultText();
        }
        if (outcome instanceof ActionOutcome.Replayed) {
            ActionOutcome.Replayed replayed = (ActionOutcome.Replayed) outcome;
            return replayed.resultRedacted() ? RESULT_REMOVED : replayed.resultText();
        }
        if (outcome instanceof ActionOutcome.Conflict) {
            ActionOutcome.Conflict conflict = (ActionOutcome.Conflict) outcome;
            if (conflict.previousUnresolved()) {
                return "a previous attempt with different arguments has an unknown outcome; not performed";
            }
            return conflict.recordedResultText() == null
                    ? "this action was already performed; its recorded result was removed from the journal; it was not"
                            + " performed again"
                    : "this action was already performed: " + conflict.recordedResultText()
                            + "; it was not performed again";
        }
        if (outcome instanceof ActionOutcome.Unknown) {
            return unknown((ActionOutcome.Unknown) outcome);
        }
        if (outcome instanceof ActionOutcome.InProgressElsewhere) {
            return "already in progress; do not repeat";
        }
        if (outcome instanceof ActionOutcome.Escalated) {
            return "could not be verified automatically; an operator has been notified; do not repeat";
        }
        if (outcome instanceof ActionOutcome.Failed) {
            return "not performed: " + ((ActionOutcome.Failed) outcome).error();
        }
        if (outcome instanceof ActionOutcome.Abandoned) {
            return "not performed: " + ((ActionOutcome.Abandoned) outcome).reason();
        }
        if (outcome instanceof ActionOutcome.JournalUnavailable) {
            return "not performed: " + ((ActionOutcome.JournalUnavailable) outcome).reason();
        }
        throw new IllegalArgumentException("unknown outcome " + outcome);
    }

    private static String unknown(ActionOutcome.Unknown unknown) {
        switch (unknown.advice()) {
            case SAFE:
                return "outcome unknown; it is safe to call this tool again with the same arguments";
            case MAY_DUPLICATE:
                return "outcome unknown; calling again may perform it twice — repeat only if a duplicate is acceptable";
            case ASK_LATER:
                Matcher matcher = CALL_AGAIN_AFTER.matcher(unknown.reason());
                return "being verified; do not repeat now; call again "
                        + (matcher.find() ? "after " + matcher.group(1) : "later") + " to get the outcome";
            default:
                throw new IllegalArgumentException("unknown advice " + unknown.advice());
        }
    }
}
