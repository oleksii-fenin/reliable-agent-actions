package io.github.oleksiifenin.raa.storage;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ClaimDecisionTest {

    @ParameterizedTest
    @EnumSource(value = ClaimVerdict.class, names = "START", mode = EnumSource.Mode.EXCLUDE)
    void onlyStartComesWithStartAttempt(ClaimVerdict verdict) {
        assertThatThrownBy(() -> new ClaimDecision(verdict, new JournalWrite.StartAttempt(2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startNeedsStartAttempt() {
        assertThatThrownBy(() -> new ClaimDecision(ClaimVerdict.START, new JournalWrite.None()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new ClaimDecision(ClaimVerdict.START, new JournalWrite.StartAttempt(1)))
                .doesNotThrowAnyException();
    }

    @Test
    void escalationAndAbandonmentNeedTheMatchingVerdict() {
        assertThatThrownBy(() -> new ClaimDecision(ClaimVerdict.CONFLICT,
                new JournalWrite.ChangeState(ActionState.ESCALATED))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClaimDecision(ClaimVerdict.ESCALATED,
                new JournalWrite.ChangeState(ActionState.ABANDONED))).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new ClaimDecision(ClaimVerdict.NEEDS_LOOKUP,
                new JournalWrite.ChangeState(ActionState.UNKNOWN))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(value = ActionState.class, names = {"EXECUTING", "SUCCEEDED", "FAILED"})
    void aClaimNeverMovesARowToExecutionResults(ActionState state) {
        assertThatThrownBy(() -> new JournalWrite.ChangeState(state)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void attemptNumbersStartAtOne() {
        assertThatThrownBy(() -> new JournalWrite.StartAttempt(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
