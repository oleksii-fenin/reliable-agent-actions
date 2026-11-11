package io.github.oleksiifenin.raa.core;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Settings shared by the four journaled policies (docs/DESIGN.md §5). Immutable; every setter returns a new policy
 * of the same type. Cross-field rules are checked by {@link #validate()}, which wiring calls for every tool.
 *
 * @param <P> the concrete policy type
 */
public abstract sealed class JournaledPolicy<P extends JournaledPolicy<P>> implements ActionPolicy
        permits IdempotentDownstream, Reconcilable, AtMostOnce, AtLeastOnce {

    static final Duration DEFAULT_DEADLINE = Duration.ofMinutes(15);
    static final int DEFAULT_MAX_ATTEMPTS = 3;
    static final Duration DEFAULT_LOOKUP_TIMEOUT = Duration.ofSeconds(5);

    /** The settings of one policy instance; {@code keyStrategy}, {@code maxEffectDuration}, {@code lookup} nullable. */
    record Settings(
            KeyStrategy keyStrategy,
            Duration maxEffectDuration,
            Duration deadline,
            int maxAttempts,
            Set<String> freeTextArguments,
            FailureClassifier failureClassifier,
            OutcomeLookup lookup,
            Duration lookupTimeout) {

        static Settings defaults(OutcomeLookup lookup) {
            return new Settings(null, null, DEFAULT_DEADLINE, DEFAULT_MAX_ATTEMPTS, Set.of(),
                    FailureClassifier.standard(), lookup, DEFAULT_LOOKUP_TIMEOUT);
        }
    }

    private final Settings settings;

    JournaledPolicy(Settings settings) {
        this.settings = settings;
    }

    /** Returns a policy of the same type with other settings; keeps type-specific fields. */
    abstract P withSettings(Settings newSettings);

    /**
     * Returns the policy name stored in the journal.
     *
     * @return for example {@code IDEMPOTENT_DOWNSTREAM}
     */
    public abstract String journalName();

    /**
     * Sets the key strategy (mandatory).
     *
     * @param keyStrategy derives the business key from a call
     * @return a new policy
     */
    public P key(KeyStrategy keyStrategy) {
        Objects.requireNonNull(keyStrategy, "keyStrategy");
        Settings s = settings;
        return withSettings(new Settings(keyStrategy, s.maxEffectDuration(), s.deadline(), s.maxAttempts(),
                s.freeTextArguments(), s.failureClassifier(), s.lookup(), s.lookupTimeout()));
    }

    /**
     * Sets the maximum duration of one attempt's effect (mandatory). Must be at least the tool's client timeout; the
     * lease is not renewed beyond it.
     *
     * @param maxEffectDuration positive duration
     * @return a new policy
     */
    public P maxEffectDuration(Duration maxEffectDuration) {
        requirePositive(maxEffectDuration, "maxEffectDuration");
        Settings s = settings;
        return withSettings(new Settings(s.keyStrategy(), maxEffectDuration, s.deadline(), s.maxAttempts(),
                s.freeTextArguments(), s.failureClassifier(), s.lookup(), s.lookupTimeout()));
    }

    /**
     * Sets the time after the first claim at which no new attempt starts; default 15 minutes.
     *
     * @param deadline positive duration
     * @return a new policy
     */
    public P deadline(Duration deadline) {
        requirePositive(deadline, "deadline");
        Settings s = settings;
        return withSettings(new Settings(s.keyStrategy(), s.maxEffectDuration(), deadline, s.maxAttempts(),
                s.freeTextArguments(), s.failureClassifier(), s.lookup(), s.lookupTimeout()));
    }

    /**
     * Sets the maximum number of attempts; default 3.
     *
     * @param maxAttempts at least 1
     * @return a new policy
     */
    public P maxAttempts(int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1: " + maxAttempts);
        }
        Settings s = settings;
        return withSettings(new Settings(s.keyStrategy(), s.maxEffectDuration(), s.deadline(), maxAttempts,
                s.freeTextArguments(), s.failureClassifier(), s.lookup(), s.lookupTimeout()));
    }

    /**
     * Declares arguments that are excluded from the conflict check (for example an e-mail body); default none.
     *
     * @param argumentNames argument names
     * @return a new policy
     */
    public P freeTextArguments(String... argumentNames) {
        Objects.requireNonNull(argumentNames, "argumentNames");
        Set<String> names = Set.copyOf(Arrays.asList(argumentNames)); // rejects null names
        Settings s = settings;
        return withSettings(new Settings(s.keyStrategy(), s.maxEffectDuration(), s.deadline(), s.maxAttempts(),
                names, s.failureClassifier(), s.lookup(), s.lookupTimeout()));
    }

    /**
     * Sets how tool exceptions are classified; default {@link FailureClassifier#standard()}.
     *
     * @param failureClassifier the classifier
     * @return a new policy
     */
    public P failureClassifier(FailureClassifier failureClassifier) {
        Objects.requireNonNull(failureClassifier, "failureClassifier");
        Settings s = settings;
        return withSettings(new Settings(s.keyStrategy(), s.maxEffectDuration(), s.deadline(), s.maxAttempts(),
                s.freeTextArguments(), failureClassifier, s.lookup(), s.lookupTimeout()));
    }

    /**
     * Declares an outcome lookup; optional except for {@link Reconcilable}, where it replaces the given one.
     *
     * @param lookup how to ask the downstream
     * @return a new policy
     */
    public P lookup(OutcomeLookup lookup) {
        Objects.requireNonNull(lookup, "lookup");
        Settings s = settings;
        return withSettings(new Settings(s.keyStrategy(), s.maxEffectDuration(), s.deadline(), s.maxAttempts(),
                s.freeTextArguments(), s.failureClassifier(), lookup, s.lookupTimeout()));
    }

    /**
     * Sets the bound of one lookup; default 5 seconds. A lookup that exceeds it counts as unreachable.
     *
     * @param lookupTimeout positive duration
     * @return a new policy
     */
    public P lookupTimeout(Duration lookupTimeout) {
        requirePositive(lookupTimeout, "lookupTimeout");
        Settings s = settings;
        return withSettings(new Settings(s.keyStrategy(), s.maxEffectDuration(), s.deadline(), s.maxAttempts(),
                s.freeTextArguments(), s.failureClassifier(), s.lookup(), lookupTimeout));
    }

    /**
     * Checks the rules wiring enforces: key strategy and maximum effect duration are set, and the maximum effect
     * duration is shorter than the deadline. Subclasses add their own rules.
     *
     * @throws IllegalStateException if a rule is broken
     */
    public void validate() {
        if (settings.keyStrategy() == null) {
            throw new IllegalStateException(journalName() + ": key(KeyStrategy) is mandatory");
        }
        if (settings.maxEffectDuration() == null) {
            throw new IllegalStateException(journalName() + ": maxEffectDuration(Duration) is mandatory");
        }
        if (settings.maxEffectDuration().compareTo(settings.deadline()) >= 0) {
            throw new IllegalStateException(journalName() + ": maxEffectDuration " + settings.maxEffectDuration()
                    + " must be shorter than the deadline " + settings.deadline());
        }
    }

    /**
     * Returns the key strategy.
     *
     * @return the strategy, empty until set
     */
    public Optional<KeyStrategy> keyStrategy() {
        return Optional.ofNullable(settings.keyStrategy());
    }

    /**
     * Returns the maximum effect duration.
     *
     * @return the duration, empty until set
     */
    public Optional<Duration> maxEffectDuration() {
        return Optional.ofNullable(settings.maxEffectDuration());
    }

    /**
     * Returns the deadline.
     *
     * @return the deadline
     */
    public Duration deadline() {
        return settings.deadline();
    }

    /**
     * Returns the maximum number of attempts.
     *
     * @return at least 1
     */
    public int maxAttempts() {
        return settings.maxAttempts();
    }

    /**
     * Returns the free-text argument names.
     *
     * @return an unmodifiable set
     */
    public Set<String> freeTextArguments() {
        return settings.freeTextArguments();
    }

    /**
     * Returns the failure classifier.
     *
     * @return the classifier
     */
    public FailureClassifier failureClassifier() {
        return settings.failureClassifier();
    }

    /**
     * Returns the outcome lookup.
     *
     * @return the lookup, empty if none is declared
     */
    public Optional<OutcomeLookup> lookup() {
        return Optional.ofNullable(settings.lookup());
    }

    /**
     * Returns the lookup timeout.
     *
     * @return the timeout
     */
    public Duration lookupTimeout() {
        return settings.lookupTimeout();
    }

    static void requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(name + " must be positive: " + duration);
        }
    }

    @Override
    public String toString() {
        return journalName();
    }
}
