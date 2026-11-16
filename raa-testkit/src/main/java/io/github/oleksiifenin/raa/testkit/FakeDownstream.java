package io.github.oleksiifenin.raa.testkit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * An HTTP downstream for kill tests (docs/DESIGN.md §9.1) that counts every effect it applies, per idempotency key.
 *
 * <ul>
 *   <li>{@code POST /effects} with header {@value #IDEMPOTENCY_KEY_HEADER} applies an effect and answers
 *       {@code effect <n> for <key>}. With deduplication on, the response is stored when the effect is applied and
 *       every later request with that key gets the stored response without a new effect (Stripe-like), even while the
 *       first response is still held.</li>
 *   <li>{@code GET /effects/<key>} is the outcome lookup: 200 with the stored response, 404 if nothing was applied,
 *       501 when lookups are off.</li>
 *   <li>With {@code holdsFirstResponse}, the first applied effect notifies {@link #awaitHeldResponse(Duration)} and its
 *       response is held until {@link #close()}: the {@link FaultPoint#EFFECT_APPLIED_RESPONSE_HELD} point.</li>
 * </ul>
 */
public final class FakeDownstream implements AutoCloseable {

    /** Request header carrying the idempotency key. */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private static final String PATH = "/effects";

    private final HttpServer server;
    private final ExecutorService executor;
    private final boolean deduplicates;
    private final boolean answersLookups;
    private final boolean holdsFirstResponse;
    private final CountDownLatch heldResponse = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final Map<String, Integer> applied = new HashMap<>();
    private final Map<String, String> stored = new HashMap<>();
    private boolean heldOnce;

    private FakeDownstream(boolean deduplicates, boolean answersLookups, boolean holdsFirstResponse) {
        this.deduplicates = deduplicates;
        this.answersLookups = answersLookups;
        this.holdsFirstResponse = holdsFirstResponse;
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "fake-downstream");
            thread.setDaemon(true);
            return thread;
        });
        try {
            this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(executor);
        server.createContext(PATH, this::handle);
        server.start();
    }

    /**
     * Starts a downstream on a free loopback port.
     *
     * @param deduplicates       whether a repeated idempotency key gets the stored response instead of a new effect
     * @param answersLookups     whether {@code GET /effects/<key>} answers
     * @param holdsFirstResponse whether the first applied effect's response is held until {@link #close()}
     * @return the running downstream
     */
    public static FakeDownstream start(boolean deduplicates, boolean answersLookups, boolean holdsFirstResponse) {
        return new FakeDownstream(deduplicates, answersLookups, holdsFirstResponse);
    }

    /**
     * Returns the base URI; effects are posted to {@code <uri>/effects}.
     *
     * @return for example {@code http://127.0.0.1:41234}
     */
    public URI uri() {
        InetSocketAddress address = server.getAddress();
        try {
            // The structured constructor brackets an IPv6 loopback address.
            return new URI("http", null, address.getAddress().getHostAddress(), address.getPort(), null, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Returns how many effects were applied for a key.
     *
     * @param idempotencyKey the key
     * @return the count, 0 if none
     */
    public synchronized int appliedEffects(String idempotencyKey) {
        return applied.getOrDefault(idempotencyKey, 0);
    }

    /**
     * Returns how many effects were applied for all keys.
     *
     * @return the total
     */
    public synchronized int totalEffects() {
        return applied.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * Returns the response stored for a key when its first effect was applied.
     *
     * @param idempotencyKey the key
     * @return the response, empty if nothing was applied
     */
    public synchronized Optional<String> storedResponse(String idempotencyKey) {
        return Optional.ofNullable(stored.get(idempotencyKey));
    }

    /**
     * Waits until the first effect was applied and its response is held.
     *
     * @param timeout how long to wait
     * @return whether it happened in time
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean awaitHeldResponse(Duration timeout) throws InterruptedException {
        return heldResponse.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /** Releases a held response and stops the server. */
    @Override
    public void close() {
        release.countDown();
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(exchange.getRequestMethod()) && PATH.equals(path)) {
                apply(exchange);
            } else if ("GET".equals(exchange.getRequestMethod()) && path.startsWith(PATH + "/")) {
                lookUp(exchange, path.substring(PATH.length() + 1));
            } else {
                respond(exchange, 404, "no such endpoint");
            }
        } catch (IOException e) {
            // The client is gone (for example killed while its response was held); nothing to answer.
        } finally {
            exchange.close();
        }
    }

    private void apply(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        String key = exchange.getRequestHeaders().getFirst(IDEMPOTENCY_KEY_HEADER);
        if (key == null || key.isBlank()) {
            respond(exchange, 400, "missing " + IDEMPOTENCY_KEY_HEADER);
            return;
        }
        String response;
        boolean hold;
        synchronized (this) {
            if (deduplicates && stored.containsKey(key)) {
                response = stored.get(key);
                hold = false;
            } else {
                int count = applied.merge(key, 1, Integer::sum);
                response = "effect " + count + " for " + key;
                stored.putIfAbsent(key, response);
                hold = holdsFirstResponse && !heldOnce;
                heldOnce |= hold;
            }
        }
        if (hold) {
            heldResponse.countDown();
            awaitRelease();
        }
        respond(exchange, 200, response);
    }

    private void lookUp(HttpExchange exchange, String key) throws IOException {
        if (!answersLookups) {
            respond(exchange, 501, "lookups are off");
            return;
        }
        Optional<String> response = storedResponse(key);
        if (response.isPresent()) {
            respond(exchange, 200, response.get());
        } else {
            respond(exchange, 404, "not found");
        }
    }

    private void awaitRelease() {
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // the server is stopping
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
