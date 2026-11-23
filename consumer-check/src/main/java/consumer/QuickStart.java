package consumer;

import static java.util.concurrent.TimeUnit.SECONDS;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.AiServiceTool;
import io.github.oleksiifenin.raa.core.ActionContext;
import io.github.oleksiifenin.raa.core.ActionKey;
import io.github.oleksiifenin.raa.core.ActionPolicy;
import io.github.oleksiifenin.raa.core.LookupResult;
import io.github.oleksiifenin.raa.core.ReliableActions;
import io.github.oleksiifenin.raa.langchain4j.JournalChatMemoryRepair;
import io.github.oleksiifenin.raa.langchain4j.ReliableTools;
import io.github.oleksiifenin.raa.langchain4j.RepairReport;
import io.github.oleksiifenin.raa.opentelemetry.OpenTelemetryActionTelemetry;
import io.github.oleksiifenin.raa.postgres.PostgresActionJournal;
import io.github.oleksiifenin.raa.storage.ActionJournal;
import io.opentelemetry.api.OpenTelemetry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.sql.DataSource;

/**
 * The calls of the README quick start, steps 1 to 4, against the published artifacts; compiling it is the check. The
 * application's own parts (data source, model, memory, mailer, payments, scheduler) are parameters or stubs.
 */
public final class QuickStart {

    private QuickStart() {
    }

    interface Assistant {
        String chat(@MemoryId String conversationId, @UserMessage String message);
    }

    interface Mailer {
        LookupResult findByReference(String reference);
    }

    interface Payments {
        String refund(long orderId, BigDecimal amount, String idempotencyKey);
    }

    static final class BillingTools {

        private final Payments payments;

        BillingTools(Payments payments) {
            this.payments = payments;
        }

        @Tool("Refund an order")
        String refundOrder(long orderId, BigDecimal amount) {
            return payments.refund(orderId, amount, ActionContext.current().idempotencyKey());
        }
    }

    static Assistant wire(DataSource dataSource, Mailer mailer, Payments payments, ChatModel model,
            ChatMemoryProvider memoryProvider, ChatMemory chatMemory, ScheduledExecutorService scheduler) {
        // 1. Journal
        ActionJournal journal = PostgresActionJournal.create(dataSource);

        // 2. Policies, with the optional telemetry
        ReliableActions actions = ReliableActions.builder(journal)
                .tool("refundOrder", ActionPolicy.idempotentDownstream(Duration.ofHours(24))
                        .key((call, args) -> ActionKey.of(call.scope(), "refund", args.longValue("orderId")))
                        .maxEffectDuration(Duration.ofSeconds(30))
                        .deadline(Duration.ofMinutes(15)))
                .tool("sendEmail", ActionPolicy.reconcilable((key, idempotencyKey) ->
                                mailer.findByReference(idempotencyKey))
                        .key((call, args) -> ActionKey.of(call.scope(), "email", args.string("ticketId")))
                        .freeTextArguments("body")
                        .maxEffectDuration(Duration.ofSeconds(20)))
                .tool("getOrder", ActionPolicy.none())
                .storeKeyText(true)
                .telemetry(OpenTelemetryActionTelemetry.create(OpenTelemetry.noop()))
                .build();

        // 3. LangChain4j tools and the memory repair
        ReliableTools reliableTools = ReliableTools.builder(actions).build();
        List<AiServiceTool> tools = reliableTools.wrap(new BillingTools(payments));
        Assistant assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .chatMemoryProvider(memoryProvider)
                .tools(tools)
                .build();
        JournalChatMemoryRepair repair = reliableTools.memoryRepair(tools);
        RepairReport report = repair.repair(chatMemory);
        if (!report.complete()) {
            throw new IllegalStateException("an attempt is still running");
        }
        ReliableTools.builder(actions).toolSpans(false).build();

        // 4. Reconciler and lease renewer
        scheduler.scheduleWithFixedDelay(actions.reconciler()::runOnce, 15, 15, SECONDS);
        actions.leaseRenewer().start();
        return assistant;
    }
}
