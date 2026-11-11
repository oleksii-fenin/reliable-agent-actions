package io.github.oleksiifenin.raa.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.oleksiifenin.raa.storage.FailureKind;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ActionPolicyTest {

    private static final KeyStrategy KEY = (call, args) -> ActionKey.of("s", "n");
    private static final OutcomeLookup LOOKUP = (key, idempotencyKey) -> new LookupResult.NotFound();

    @Test
    void journaledPolicies_haveTheDocumentedDefaults() {
        AtLeastOnce policy = ActionPolicy.atLeastOnce();

        assertThat(policy.deadline()).isEqualTo(Duration.ofMinutes(15));
        assertThat(policy.maxAttempts()).isEqualTo(3);
        assertThat(policy.lookupTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.freeTextArguments()).isEmpty();
        assertThat(policy.keyStrategy()).isEmpty();
        assertThat(policy.maxEffectDuration()).isEmpty();
        assertThat(policy.lookup()).isEmpty();
    }

    @Test
    void journalNames_matchTheSchema() {
        assertThat(ActionPolicy.idempotentDownstream(Duration.ofHours(1)).journalName())
                .isEqualTo("IDEMPOTENT_DOWNSTREAM");
        assertThat(ActionPolicy.reconcilable(LOOKUP).journalName()).isEqualTo("RECONCILABLE");
        assertThat(ActionPolicy.atMostOnce().journalName()).isEqualTo("AT_MOST_ONCE");
        assertThat(ActionPolicy.atLeastOnce().journalName()).isEqualTo("AT_LEAST_ONCE");
        assertThat(ActionPolicy.none()).hasToString("NONE").isSameAs(ActionPolicy.none());
    }

    @Test
    void setters_returnNewPolicies_andLeaveTheOriginalUnchanged() {
        IdempotentDownstream original = ActionPolicy.idempotentDownstream(Duration.ofHours(24));

        IdempotentDownstream changed = original.key(KEY).maxEffectDuration(Duration.ofSeconds(10))
                .deadline(Duration.ofMinutes(5)).maxAttempts(5).freeTextArguments("body")
                .lookup(LOOKUP).lookupTimeout(Duration.ofSeconds(2));

        assertThat(changed).isNotSameAs(original);
        assertThat(changed.downstreamDedupWindow()).isEqualTo(Duration.ofHours(24));
        assertThat(changed.keyStrategy()).containsSame(KEY);
        assertThat(changed.maxEffectDuration()).contains(Duration.ofSeconds(10));
        assertThat(changed.deadline()).isEqualTo(Duration.ofMinutes(5));
        assertThat(changed.maxAttempts()).isEqualTo(5);
        assertThat(changed.freeTextArguments()).containsExactly("body");
        assertThat(changed.lookup()).containsSame(LOOKUP);
        assertThat(changed.lookupTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(original.keyStrategy()).isEmpty();
        assertThat(original.deadline()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void reconcilable_alwaysHasALookup() {
        OutcomeLookup other = (key, idempotencyKey) -> new LookupResult.Unreachable("down");

        assertThat(ActionPolicy.reconcilable(LOOKUP).lookup()).containsSame(LOOKUP);
        assertThat(ActionPolicy.reconcilable(LOOKUP).lookup(other).lookup()).containsSame(other);
        assertThatThrownBy(() -> ActionPolicy.reconcilable(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void validate_acceptsACompletePolicy() {
        ActionPolicy.atMostOnce().key(KEY).maxEffectDuration(Duration.ofSeconds(10)).validate();
        ActionPolicy.idempotentDownstream(Duration.ofMinutes(15)).key(KEY).maxEffectDuration(Duration.ofSeconds(10))
                .validate();
    }

    @Test
    void validate_requiresKeyAndMaxEffectDuration() {
        assertThatThrownBy(() -> ActionPolicy.atLeastOnce().maxEffectDuration(Duration.ofSeconds(1)).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("key(KeyStrategy)");
        assertThatThrownBy(() -> ActionPolicy.atLeastOnce().key(KEY).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("maxEffectDuration");
    }

    @Test
    void validate_requiresMaxEffectDurationShorterThanTheDeadline() {
        AtLeastOnce equal = ActionPolicy.atLeastOnce().key(KEY).maxEffectDuration(Duration.ofMinutes(1))
                .deadline(Duration.ofMinutes(1));

        assertThatThrownBy(equal::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shorter than the deadline");
    }

    @Test
    void validate_rejectsADeadlineBeyondTheDownstreamDedupWindow() {
        IdempotentDownstream policy = ActionPolicy.idempotentDownstream(Duration.ofMinutes(10)).key(KEY)
                .maxEffectDuration(Duration.ofSeconds(10));

        assertThatThrownBy(policy::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dedup window");
        policy.deadline(Duration.ofMinutes(10)).validate();
    }

    @Test
    void setters_rejectInvalidValues() {
        AtLeastOnce policy = ActionPolicy.atLeastOnce();

        assertThatThrownBy(() -> policy.maxEffectDuration(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.deadline(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.maxAttempts(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.lookupTimeout(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.freeTextArguments("a", null)).isInstanceOf(NullPointerException.class);
        assertThat(policy.freeTextArguments("a", "a").freeTextArguments()).containsExactly("a");
        assertThatThrownBy(() -> policy.key(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ActionPolicy.idempotentDownstream(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void standardFailureClassifier_isDefiniteBeforeTheEffectOrOnRejection_elseIndeterminate() {
        FailureClassifier classifier = ActionPolicy.atLeastOnce().failureClassifier();

        assertThat(classifier.classify(new RuntimeException(), false)).isEqualTo(FailureKind.DEFINITE);
        assertThat(classifier.classify(new ActionRejectedException("no"), true)).isEqualTo(FailureKind.DEFINITE);
        assertThat(classifier.classify(new RuntimeException(), true)).isEqualTo(FailureKind.INDETERMINATE);
    }
}
