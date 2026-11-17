package io.github.oleksiifenin.raa.examples.refund;

import io.github.oleksiifenin.raa.core.LookupResult;
import java.io.IOException;
import java.math.BigDecimal;

/** The payment API the refund tool calls. */
public interface Payments {

    /**
     * Refunds an order.
     *
     * @param orderId        the order
     * @param amount         the amount
     * @param idempotencyKey the key the API deduplicates by (if it does)
     * @return the API's confirmation
     * @throws IOException if the API cannot be reached or answers with an error
     */
    String refund(String orderId, BigDecimal amount, String idempotencyKey) throws IOException;

    /**
     * Asks the API whether a refund with this key was made.
     *
     * @param idempotencyKey the key
     * @return found with the confirmation, not found, or unreachable
     */
    LookupResult findRefund(String idempotencyKey);
}
