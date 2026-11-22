package io.github.oleksiifenin.raa.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.oleksiifenin.raa.storage.ActionJournal;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Wiring rules (R5): an invalid policy fails the build of the wiring, not the first call. */
class ReliableActionsTest {

    private static final ActionJournal UNUSED_JOURNAL = (ActionJournal) Proxy.newProxyInstance(
            ActionJournal.class.getClassLoader(), new Class<?>[] {ActionJournal.class}, (proxy, method, args) -> {
                throw new AssertionError("the journal must not be used while wiring");
            });
    private static final KeyStrategy KEY = (call, args) -> ActionKey.of("s", call.toolName());

    @Test
    void policy_returnsTheDeclaredPolicy_orNothing() {
        ActionPolicy none = ActionPolicy.none();
        ReliableActions actions = ReliableActions.builder(UNUSED_JOURNAL).tool("getOrder", none).build();

        assertThat(actions.policy("getOrder")).containsSame(none);
        assertThat(actions.policy("refund")).isEmpty();
    }

    @Test
    void build_rejectsAJournaledToolWithoutKeyStrategy() {
        ReliableActions.Builder builder = ReliableActions.builder(UNUSED_JOURNAL)
                .tool("refund", ActionPolicy.atMostOnce().maxEffectDuration(Duration.ofSeconds(10)));

        assertThatThrownBy(builder::build).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tool refund").hasMessageContaining("key(KeyStrategy) is mandatory");
    }

    @Test
    void build_rejectsAMaximumEffectDurationNotShorterThanTheDeadline() {
        ReliableActions.Builder builder = ReliableActions.builder(UNUSED_JOURNAL).tool("refund",
                ActionPolicy.atLeastOnce().key(KEY).maxEffectDuration(Duration.ofMinutes(15)));

        assertThatThrownBy(builder::build).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be shorter than the deadline");
    }

    @Test
    void build_rejectsADeadlineBeyondTheDownstreamDedupWindow() {
        ReliableActions.Builder builder = ReliableActions.builder(UNUSED_JOURNAL).tool("refund",
                ActionPolicy.idempotentDownstream(Duration.ofMinutes(10)).key(KEY)
                        .maxEffectDuration(Duration.ofSeconds(10)));

        assertThatThrownBy(builder::build).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not exceed the downstream dedup window");
    }

    @Test
    void tool_rejectsASecondPolicyForTheSameTool() {
        ReliableActions.Builder builder = ReliableActions.builder(UNUSED_JOURNAL).tool("search", ActionPolicy.none());

        assertThatThrownBy(() -> builder.tool("search", ActionPolicy.none()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void settings_mustBePositive() {
        ReliableActions.Builder builder = ReliableActions.builder(UNUSED_JOURNAL);

        assertThatThrownBy(() -> builder.lease(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.inProgressWait(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.recordRetryBudget(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.retention(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void build_rejectsARetentionNotLongerThanAToolsDeadline() {
        ReliableActions.Builder builder = ReliableActions.builder(UNUSED_JOURNAL).retention(Duration.ofMinutes(15))
                .tool("refund", ActionPolicy.atLeastOnce().key(KEY).maxEffectDuration(Duration.ofSeconds(10)));

        assertThatThrownBy(builder::build).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tool refund").hasMessageContaining("must exceed the deadline PT15M");
        assertThat(builder.retention(Duration.ofMinutes(16)).build()).isNotNull();
    }

    @Test
    void build_returnsOneRunnerAndOneRenewer() {
        ReliableActions actions = ReliableActions.builder(UNUSED_JOURNAL)
                .tool("refund", ActionPolicy.atMostOnce().key(KEY).maxEffectDuration(Duration.ofSeconds(10)))
                .build();

        assertThat(actions.runner()).isSameAs(actions.runner());
        assertThat(actions.leaseRenewer()).isSameAs(actions.leaseRenewer());
    }

    @Test
    void run_rejectsToolsWithoutPolicyAndReadOnlyTools() {
        ActionRunner runner = ReliableActions.builder(UNUSED_JOURNAL).tool("search", ActionPolicy.none()).build()
                .runner();
        ToolCall unknown = new ToolCall("delete", "{}", Map.of(), null, "s");
        ToolCall readOnly = new ToolCall("search", "{}", Map.of(), null, "s");

        assertThatThrownBy(() -> runner.run(unknown, context -> "x")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no policy");
        assertThatThrownBy(() -> runner.run(readOnly, context -> "x")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("none()");
    }
}
