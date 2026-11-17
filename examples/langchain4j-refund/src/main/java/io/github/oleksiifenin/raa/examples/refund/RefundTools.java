package io.github.oleksiifenin.raa.examples.refund;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.github.oleksiifenin.raa.core.ActionContext;
import java.io.IOException;
import java.math.BigDecimal;

/** The agent's tools, written as plain LangChain4j tools; the library is added by wiring only (ADR-0002). */
public final class RefundTools {

    private final Payments payments;

    /**
     * Creates the tools.
     *
     * @param payments the payment API
     */
    public RefundTools(Payments payments) {
        this.payments = payments;
    }

    /**
     * Refunds an order; side-effecting, journaled.
     *
     * @param orderId the order
     * @param amount  the amount
     * @return the payment API's confirmation
     * @throws IOException if the payment API fails (the outcome is then unknown)
     */
    @Tool("Refunds an order")
    public String refund(@P("the order id") String orderId, @P("the amount") BigDecimal amount) throws IOException {
        // The only library-specific line: pass the stable key downstream.
        return payments.refund(orderId, amount, ActionContext.current().idempotencyKey());
    }

    /**
     * Looks an order up; read-only, not journaled.
     *
     * @param orderId the order
     * @return its status
     */
    @Tool("Looks an order up")
    public String lookUpOrder(@P("the order id") String orderId) {
        return "order " + orderId + " is paid";
    }
}
