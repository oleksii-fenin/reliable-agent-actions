package io.github.oleksiifenin.raa.examples.refund;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolExecutor;
import io.github.oleksiifenin.raa.core.ActionKey;
import io.github.oleksiifenin.raa.core.ActionPolicy;
import io.github.oleksiifenin.raa.core.JournaledPolicy;
import io.github.oleksiifenin.raa.core.ReliableActions;
import io.github.oleksiifenin.raa.langchain4j.JournalChatMemoryRepair;
import io.github.oleksiifenin.raa.langchain4j.ReliableTools;
import io.github.oleksiifenin.raa.langchain4j.RepairReport;
import io.github.oleksiifenin.raa.postgres.PostgresActionJournal;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/**
 * A refund agent wired with reliable-agent-actions: the action journal and the chat memory in the application's own
 * PostgreSQL, the refund tool journaled, the lookup tool read-only, and memory repair after a restart. The wiring is the
 * point of the example; see {@link RefundDemo} for a run.
 */
public final class RefundAgent {

    /** The AI service. */
    public interface Assistant {
        /**
         * Sends a message in a conversation.
         *
         * @param conversationId the conversation; the scope of every action key (ADR-0005)
         * @param message        the user's message
         * @return the answer
         */
        String chat(@MemoryId String conversationId, @UserMessage String message);
    }

    private static final String JOURNAL_SCHEMA = "/io/github/oleksiifenin/raa/postgres/schema/V1__raa_action.sql";
    /** Advisory-lock id for the schema installation of this example. */
    private static final long SCHEMA_LOCK = 0x7261615f73636865L;

    private final ReliableActions actions;
    private final JdbcChatMemoryStore memoryStore;
    private final JournalChatMemoryRepair repair;
    private final Assistant assistant;

    private RefundAgent(Builder builder) {
        installJournalSchema(builder.dataSource);
        JournaledPolicy<?> refund = builder.refundPolicy
                .key((call, args) -> ActionKey.of(call.scope(), "refund", args.string("orderId")))
                .maxEffectDuration(builder.maxEffectDuration);
        this.actions = ReliableActions.builder(PostgresActionJournal.create(builder.dataSource))
                .tool("refund", refund)
                .tool("lookUpOrder", ActionPolicy.none())
                .lease(builder.lease)
                .build();
        this.memoryStore = new JdbcChatMemoryStore(builder.dataSource);
        ReliableTools reliableTools = ReliableTools.builder(actions).build();
        List<AiServiceTool> tools = reliableTools.wrap(new RefundTools(builder.payments)).stream()
                .map(tool -> tool.toBuilder().toolExecutor(builder.toolDecorator.apply(tool.toolExecutor())).build())
                .collect(Collectors.toList());
        this.repair = reliableTools.memoryRepair(tools);
        this.assistant = AiServices.builder(Assistant.class)
                .chatModel(builder.chatModel)
                .chatMemoryProvider(this::memory)
                .tools(tools)
                .build();
    }

    /**
     * Starts the wiring.
     *
     * @param dataSource the application's PostgreSQL (journal and chat memory)
     * @param payments   the payment API
     * @param chatModel  the model
     * @return a builder
     */
    public static Builder builder(DataSource dataSource, Payments payments, ChatModel chatModel) {
        return new Builder(dataSource, payments, chatModel);
    }

    /**
     * Returns the AI service.
     *
     * @return the assistant
     */
    public Assistant assistant() {
        return assistant;
    }

    /**
     * Answers tool calls left without a result in a conversation, for example after a restart; call it before the next
     * message.
     *
     * @param conversationId the conversation
     * @return what was repaired
     */
    public RepairReport repair(String conversationId) {
        return repair.repair(conversationId, memory(conversationId));
    }

    /**
     * Drops a conversation's memory (a conversation that is retried from the start).
     *
     * @param conversationId the conversation
     */
    public void forget(String conversationId) {
        memoryStore.deleteMessages(conversationId);
    }

    /**
     * Returns the wiring, for the lease renewer and the reconciler.
     *
     * @return the reliable actions
     */
    public ReliableActions actions() {
        return actions;
    }

    private ChatMemory memory(Object conversationId) {
        return MessageWindowChatMemory.builder().id(conversationId).maxMessages(50).chatMemoryStore(memoryStore).build();
    }

    /**
     * Installs the journal schema once, also when several agents start together: an advisory lock serializes the check
     * and the installation. A real application runs the schema with its migration tool (Flyway, Liquibase) instead.
     */
    private static void installJournalSchema(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("select pg_advisory_xact_lock(" + SCHEMA_LOCK + ")");
            boolean installed;
            try (ResultSet resultSet = statement.executeQuery("select to_regclass('raa_action') is not null")) {
                resultSet.next();
                installed = resultSet.getBoolean(1);
            }
            if (!installed) {
                try (InputStream in = Objects.requireNonNull(
                        PostgresActionJournal.class.getResourceAsStream(JOURNAL_SCHEMA), "journal schema")) {
                    statement.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("could not install the journal schema", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Settings of {@link RefundAgent}. */
    public static final class Builder {

        private final DataSource dataSource;
        private final Payments payments;
        private final ChatModel chatModel;
        private JournaledPolicy<?> refundPolicy = ActionPolicy.idempotentDownstream(Duration.ofHours(24));
        private Duration maxEffectDuration = Duration.ofSeconds(30);
        private Duration lease = Duration.ofSeconds(30);
        private UnaryOperator<ToolExecutor> toolDecorator = UnaryOperator.identity();

        private Builder(DataSource dataSource, Payments payments, ChatModel chatModel) {
            this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
            this.payments = Objects.requireNonNull(payments, "payments");
            this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        }

        /**
         * Sets the refund tool's policy; default {@code IDEMPOTENT_DOWNSTREAM} (the payment API deduplicates by key for
         * 24 hours). The key strategy is set by the agent.
         *
         * @param refundPolicy the policy
         * @return this builder
         */
        public Builder refundPolicy(JournaledPolicy<?> refundPolicy) {
            this.refundPolicy = Objects.requireNonNull(refundPolicy, "refundPolicy");
            return this;
        }

        /**
         * Sets the refund tool's maximum effect duration; default 30 seconds, at least the payment client's timeout.
         *
         * @param maxEffectDuration the duration
         * @return this builder
         */
        public Builder maxEffectDuration(Duration maxEffectDuration) {
            this.maxEffectDuration = Objects.requireNonNull(maxEffectDuration, "maxEffectDuration");
            return this;
        }

        /**
         * Sets the lease; default 30 seconds.
         *
         * @param lease the lease
         * @return this builder
         */
        public Builder lease(Duration lease) {
            this.lease = Objects.requireNonNull(lease, "lease");
            return this;
        }

        /**
         * Decorates every tool executor after the journal's, for instrumentation such as tracing (the kill matrix uses
         * it to stop the process after the journal recorded a result and before the memory has it).
         *
         * @param toolDecorator the decorator
         * @return this builder
         */
        public Builder toolDecorator(UnaryOperator<ToolExecutor> toolDecorator) {
            this.toolDecorator = Objects.requireNonNull(toolDecorator, "toolDecorator");
            return this;
        }

        /**
         * Builds the agent; installs the journal schema if the database has none.
         *
         * @return the agent
         */
        public RefundAgent build() {
            return new RefundAgent(this);
        }
    }
}
