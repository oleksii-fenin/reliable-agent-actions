package io.github.oleksiifenin.raa.core;

import io.github.oleksiifenin.raa.storage.ActionJournal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The wiring of one application: the journal, the policy of every tool and the runtime settings
 * (docs/DESIGN.md §5). Built once; {@link #runner()} and {@link #leaseRenewer()} return the same instances on every
 * call. There is no clock setting: every time decision uses the journal's database time.
 */
public final class ReliableActions {

    static final Duration DEFAULT_LEASE = Duration.ofSeconds(30);
    static final Duration DEFAULT_IN_PROGRESS_WAIT = Duration.ofSeconds(2);
    static final Duration DEFAULT_RECORD_RETRY_BUDGET = Duration.ofSeconds(3);

    private final LeaseRenewer leaseRenewer;
    private final ActionRunner runner;
    private final Reconciler reconciler;

    private ReliableActions(Builder builder) {
        String owner = UUID.randomUUID().toString(); // lease owner id of this wiring
        this.leaseRenewer = new LeaseRenewer(builder.journal, owner, builder.lease);
        this.runner = new ActionRunner(builder.journal, Map.copyOf(builder.policies), owner, builder.lease,
                builder.inProgressWait, builder.recordRetryBudget, leaseRenewer, builder.storeKeyText);
        this.reconciler = new Reconciler(builder.journal, Map.copyOf(builder.policies), Reconciler.DEFAULT_HOLD,
                Reconciler.DEFAULT_BATCH_SIZE);
    }

    /**
     * Starts the wiring for a journal.
     *
     * @param journal the action journal, for example {@code PostgresActionJournal.create(dataSource)}
     * @return a builder
     */
    public static Builder builder(ActionJournal journal) {
        return new Builder(journal);
    }

    /**
     * Returns the runner that executes tool calls.
     *
     * @return the runner
     */
    public ActionRunner runner() {
        return runner;
    }

    /**
     * Returns the lease renewer of this wiring; the application starts and closes it.
     *
     * @return the renewer
     */
    public LeaseRenewer leaseRenewer() {
        return leaseRenewer;
    }

    /**
     * Returns the reconciler; the application calls {@link Reconciler#runOnce()} periodically (every 15 seconds is
     * the documented cadence), on any number of processes.
     *
     * @return the reconciler
     */
    public Reconciler reconciler() {
        return reconciler;
    }

    /** Collects the wiring; {@link #build()} validates every policy. */
    public static final class Builder {

        private final ActionJournal journal;
        private final Map<String, ActionPolicy> policies = new LinkedHashMap<>();
        private Duration lease = DEFAULT_LEASE;
        private Duration inProgressWait = DEFAULT_IN_PROGRESS_WAIT;
        private Duration recordRetryBudget = DEFAULT_RECORD_RETRY_BUDGET;
        private boolean storeKeyText;

        private Builder(ActionJournal journal) {
            this.journal = Objects.requireNonNull(journal, "journal");
        }

        /**
         * Declares the policy of a tool. Every tool the runner is asked to run needs one.
         *
         * @param toolName the tool name as the model calls it
         * @param policy   the tool's policy
         * @return this builder
         * @throws IllegalArgumentException if the tool already has a policy
         */
        public Builder tool(String toolName, ActionPolicy policy) {
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(policy, "policy");
            if (policies.putIfAbsent(toolName, policy) != null) {
                throw new IllegalArgumentException("tool " + toolName + " already has a policy");
            }
            return this;
        }

        /**
         * Sets the lease of a running attempt; default 30 seconds. The lease renewer extends it every half lease.
         *
         * @param lease positive duration
         * @return this builder
         */
        public Builder lease(Duration lease) {
            this.lease = requirePositive(lease, "lease");
            return this;
        }

        /**
         * Sets how long a call waits for another call of the same action to finish; default 2 seconds.
         *
         * @param inProgressWait positive duration
         * @return this builder
         */
        public Builder inProgressWait(Duration inProgressWait) {
            this.inProgressWait = requirePositive(inProgressWait, "inProgressWait");
            return this;
        }

        /**
         * Sets how long writing an attempt's outcome is retried; default 3 seconds.
         *
         * @param recordRetryBudget positive duration
         * @return this builder
         */
        public Builder recordRetryBudget(Duration recordRetryBudget) {
            this.recordRetryBudget = requirePositive(recordRetryBudget, "recordRetryBudget");
            return this;
        }

        /**
         * Stores the action key as readable text in the journal (ADR-0004); default {@code false}. Needed for the
         * {@link Reconciler} to look up actions whose conversation does not resume. The text contains the business
         * identifiers of the key (for example an order id or an e-mail address).
         *
         * @param storeKeyText whether to store key text
         * @return this builder
         */
        public Builder storeKeyText(boolean storeKeyText) {
            this.storeKeyText = storeKeyText;
            return this;
        }

        /**
         * Validates every journaled policy and builds the wiring.
         *
         * @return the wiring
         * @throws IllegalStateException if a policy breaks a wiring rule (see {@link JournaledPolicy#validate()})
         */
        public ReliableActions build() {
            policies.forEach((toolName, policy) -> {
                if (policy instanceof JournaledPolicy<?>) {
                    try {
                        ((JournaledPolicy<?>) policy).validate();
                    } catch (IllegalStateException e) {
                        throw new IllegalStateException("tool " + toolName + ": " + e.getMessage(), e);
                    }
                }
            });
            return new ReliableActions(this);
        }

        private static Duration requirePositive(Duration duration, String name) {
            Objects.requireNonNull(duration, name);
            if (duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException(name + " must be positive: " + duration);
            }
            return duration;
        }
    }
}
