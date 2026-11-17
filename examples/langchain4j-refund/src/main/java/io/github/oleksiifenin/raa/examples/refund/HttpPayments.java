package io.github.oleksiifenin.raa.examples.refund;

import io.github.oleksiifenin.raa.core.LookupResult;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * {@link Payments} over HTTP: {@code POST /effects} with header {@code Idempotency-Key}, and {@code GET /effects/<key>}
 * as the outcome lookup (the protocol of the test kit's {@code FakeDownstream}, which stands in for a payment API).
 */
public final class HttpPayments implements Payments {

    private final URI baseUri;
    private final Duration timeout;
    private final HttpClient http;

    /**
     * Creates the client.
     *
     * @param baseUri the API's base URI
     * @param timeout the request timeout; must be below the refund tool's maximum effect duration
     */
    public HttpPayments(URI baseUri, Duration timeout) {
        this.baseUri = baseUri;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public String refund(String orderId, BigDecimal amount, String idempotencyKey) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUri + "/effects")).timeout(timeout)
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"orderId\":\"" + orderId + "\",\"amount\":" + amount.toPlainString() + "}"))
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) {
            throw new IOException("the payment API answered HTTP " + response.statusCode());
        }
        return response.body();
    }

    @Override
    public LookupResult findRefund(String idempotencyKey) {
        try {
            HttpResponse<String> response = send(HttpRequest.newBuilder(
                    URI.create(baseUri + "/effects/" + idempotencyKey)).timeout(timeout).GET().build());
            if (response.statusCode() == 200) {
                return new LookupResult.Found(response.body());
            }
            return response.statusCode() == 404
                    ? new LookupResult.NotFound()
                    : new LookupResult.Unreachable("HTTP " + response.statusCode());
        } catch (IOException e) {
            return new LookupResult.Unreachable(e.toString());
        }
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while calling the payment API", e);
        }
    }
}
