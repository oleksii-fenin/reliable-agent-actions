package io.github.oleksiifenin.raa.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ActionContextTest {

    private static final ActionKey KEY = ActionKey.of("conversation-1", "refund", "order-42");

    @Test
    void current_isTheContextOfTheRunningEffect() throws Exception {
        ActionContext context = new ActionContext(KEY, "raa-key", 2);

        String seen = context.runOnCurrentThread(given -> {
            assertThat(given).isSameAs(context);
            assertThat(ActionContext.current()).isSameAs(context);
            return ActionContext.current().idempotencyKey() + "/" + ActionContext.current().attempt();
        });

        assertThat(seen).isEqualTo("raa-key/2");
        assertThat(context.key()).isEqualTo(KEY);
    }

    @Test
    void current_outsideAnEffect_throwsInsteadOfReturningNull() {
        assertThatThrownBy(ActionContext::current).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no action is executing");
    }

    @Test
    void current_isRemovedAfterTheEffect_evenWhenItThrows() {
        ActionContext context = new ActionContext(KEY, "raa-key", 1);

        assertThatThrownBy(() -> context.runOnCurrentThread(given -> {
            throw new IllegalStateException("boom");
        })).hasMessage("boom");

        assertThatThrownBy(ActionContext::current).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void current_onAnotherThreadStartedByTheEffect_throws() throws Exception {
        ActionContext context = new ActionContext(KEY, "raa-key", 1);

        String seenOnOtherThread = context.runOnCurrentThread(given -> CompletableFuture.supplyAsync(() -> {
            try {
                return "context " + ActionContext.current().idempotencyKey();
            } catch (IllegalStateException e) {
                return "no context";
            }
        }).get(10, TimeUnit.SECONDS));

        assertThat(seenOnOtherThread).isEqualTo("no context");
    }

    @Test
    void nestedEffects_restoreTheOuterContext() throws Exception {
        ActionContext outer = new ActionContext(KEY, "outer", 1);
        ActionContext inner = new ActionContext(KEY, "inner", 1);

        String seen = outer.runOnCurrentThread(given -> {
            inner.runOnCurrentThread(nested -> ActionContext.current().idempotencyKey());
            return ActionContext.current().idempotencyKey();
        });

        assertThat(seen).isEqualTo("outer");
    }
}
