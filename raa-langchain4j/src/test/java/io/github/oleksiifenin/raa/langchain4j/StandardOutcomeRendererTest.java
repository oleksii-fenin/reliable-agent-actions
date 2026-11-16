package io.github.oleksiifenin.raa.langchain4j;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oleksiifenin.raa.core.ActionOutcome;
import io.github.oleksiifenin.raa.core.RepeatAdvice;
import io.github.oleksiifenin.raa.core.ToolCall;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** One test per line of docs/DESIGN.md §6, "What the model is told". */
class StandardOutcomeRendererTest {

    private static final ToolCall CALL = new ToolCall("refund", "{}", Map.of(), "id", "c");
    private final OutcomeRenderer renderer = OutcomeRenderer.standard();

    @Test
    void resultsAreTheResultTextVerbatim() {
        assertThat(render(new ActionOutcome.Succeeded("refunded r-1", 1))).isEqualTo("refunded r-1");
        assertThat(render(new ActionOutcome.Replayed("refunded r-1", Instant.EPOCH, false))).isEqualTo("refunded r-1");
        assertThat(render(new ActionOutcome.Replayed("found r-1", Instant.EPOCH, true))).isEqualTo("found r-1");
    }

    @Test
    void conflicts() {
        assertThat(render(new ActionOutcome.Conflict("refunded 10", false)))
                .isEqualTo("this action was already performed: refunded 10; it was not performed again");
        assertThat(render(new ActionOutcome.Conflict(null, true)))
                .isEqualTo("a previous attempt with different arguments has an unknown outcome; not performed");
    }

    @Test
    void unknownOutcomes_tellWhetherRepeatingIsSafe() {
        assertThat(render(new ActionOutcome.Unknown(RepeatAdvice.SAFE, "timeout")))
                .isEqualTo("outcome unknown; it is safe to call this tool again with the same arguments");
        assertThat(render(new ActionOutcome.Unknown(RepeatAdvice.MAY_DUPLICATE, "timeout")))
                .isEqualTo("outcome unknown; calling again may perform it twice — repeat only if a duplicate is"
                        + " acceptable");
        assertThat(render(new ActionOutcome.Unknown(RepeatAdvice.ASK_LATER,
                "the outcome is being verified; call again after 2026-11-16T10:00:03Z")))
                .isEqualTo("being verified; do not repeat now; call again after 2026-11-16T10:00:03Z to get the"
                        + " outcome");
        assertThat(render(new ActionOutcome.Unknown(RepeatAdvice.ASK_LATER, "interrupted; call again later")))
                .isEqualTo("being verified; do not repeat now; call again later to get the outcome");
    }

    @Test
    void inProgressAndEscalated() {
        assertThat(render(new ActionOutcome.InProgressElsewhere(Instant.EPOCH)))
                .isEqualTo("already in progress; do not repeat");
        assertThat(render(new ActionOutcome.Escalated("lookup unreachable")))
                .isEqualTo("could not be verified automatically; an operator has been notified; do not repeat");
    }

    @Test
    void notPerformed_withTheReason() {
        assertThat(render(new ActionOutcome.Failed("card declined", true))).isEqualTo("not performed: card declined");
        assertThat(render(new ActionOutcome.Abandoned("no attempts are left")))
                .isEqualTo("not performed: no attempts are left");
        assertThat(render(new ActionOutcome.JournalUnavailable("connection refused")))
                .isEqualTo("not performed: connection refused");
    }

    private String render(ActionOutcome outcome) {
        return renderer.render(outcome, CALL);
    }
}
