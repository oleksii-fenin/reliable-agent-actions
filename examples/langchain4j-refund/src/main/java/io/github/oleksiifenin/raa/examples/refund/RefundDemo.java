package io.github.oleksiifenin.raa.examples.refund;

import io.github.oleksiifenin.raa.testkit.FakeDownstream;
import java.time.Duration;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the refund agent against a PostgreSQL in Docker (Testcontainers) and a fake payment API: the customer asks twice,
 * the model calls the refund tool twice with different tool-call ids, and the payment API sees one refund.
 * {@code ./gradlew :examples:langchain4j-refund:run} (needs Docker).
 */
public final class RefundDemo {

    private RefundDemo() {
    }

    public static void main(String[] args) {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");
             FakeDownstream paymentApi = FakeDownstream.start(false, true, false)) { // no deduplication downstream
            postgres.start();
            PGSimpleDataSource dataSource = new PGSimpleDataSource();
            dataSource.setUrl(postgres.getJdbcUrl());
            dataSource.setUser(postgres.getUsername());
            dataSource.setPassword(postgres.getPassword());
            String refund = "{\"orderId\":\"order-42\",\"amount\":30}";

            RefundAgent agent = RefundAgent.builder(dataSource,
                            new HttpPayments(paymentApi.uri(), Duration.ofSeconds(5)),
                            new ScriptedRefundModel("first-", refund))
                    .refundPolicy(io.github.oleksiifenin.raa.core.ActionPolicy.atMostOnce())
                    .build();
            agent.actions().leaseRenewer().start();
            System.out.println("1st answer: " + agent.assistant().chat("conversation-1", "refund order 42"));
            System.out.println("2nd answer: " + agent.assistant().chat("conversation-1", "please refund order 42"));
            System.out.println("refunds the payment API made: " + paymentApi.totalEffects());
            agent.actions().leaseRenewer().close();
        }
    }
}
