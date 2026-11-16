package io.github.oleksiifenin.raa.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class FakeDownstreamTest {

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void withoutDeduplication_everyRequestIsAnEffect() throws Exception {
        try (FakeDownstream downstream = FakeDownstream.start(false, false, false)) {
            HttpResponse<String> first = post(downstream, "k1");
            HttpResponse<String> second = post(downstream, "k1");

            assertThat(first.body()).isEqualTo("effect 1 for k1");
            assertThat(second.body()).isEqualTo("effect 2 for k1");
            assertThat(downstream.appliedEffects("k1")).isEqualTo(2);
            assertThat(downstream.storedResponse("k1")).contains("effect 1 for k1");
        }
    }

    @Test
    void withDeduplication_aRepeatedKeyGetsTheStoredResponse_withoutAnEffect() throws Exception {
        try (FakeDownstream downstream = FakeDownstream.start(true, false, false)) {
            post(downstream, "k1");
            HttpResponse<String> repeated = post(downstream, "k1");
            post(downstream, "k2");

            assertThat(repeated.body()).isEqualTo("effect 1 for k1");
            assertThat(downstream.appliedEffects("k1")).isEqualTo(1);
            assertThat(downstream.totalEffects()).isEqualTo(2);
        }
    }

    @Test
    void lookups_answerFoundOrNotFound_onlyWhenEnabled() throws Exception {
        try (FakeDownstream on = FakeDownstream.start(false, true, false);
             FakeDownstream off = FakeDownstream.start(false, false, false)) {
            post(on, "k1");

            assertThat(get(on, "k1").statusCode()).isEqualTo(200);
            assertThat(get(on, "k1").body()).isEqualTo("effect 1 for k1");
            assertThat(get(on, "k2").statusCode()).isEqualTo(404);
            assertThat(get(off, "k1").statusCode()).isEqualTo(501);
        }
    }

    @Test
    void heldResponse_isAppliedAndStoredFirst_soADeduplicatedRetryIsAnsweredMeanwhile() throws Exception {
        try (FakeDownstream downstream = FakeDownstream.start(true, true, true)) {
            CompletableFuture<HttpResponse<String>> held = client.sendAsync(postRequest(downstream, "k1"),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(downstream.awaitHeldResponse(Duration.ofSeconds(10))).isTrue();
            HttpResponse<String> retry = post(downstream, "k1");
            HttpResponse<String> lookup = get(downstream, "k1");

            assertThat(held).isNotDone();
            assertThat(retry.body()).isEqualTo("effect 1 for k1");
            assertThat(lookup.body()).isEqualTo("effect 1 for k1");
            assertThat(downstream.appliedEffects("k1")).isEqualTo(1);
        }
    }

    @Test
    void missingIdempotencyKey_isRejected_withoutAnEffect() throws Exception {
        try (FakeDownstream downstream = FakeDownstream.start(false, false, false)) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(effects(downstream))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(downstream.totalEffects()).isZero();
        }
    }

    private HttpResponse<String> post(FakeDownstream downstream, String key) throws Exception {
        return client.send(postRequest(downstream, key), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(FakeDownstream downstream, String key) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(effects(downstream) + "/" + key)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest postRequest(FakeDownstream downstream, String key) {
        return HttpRequest.newBuilder(effects(downstream)).header(FakeDownstream.IDEMPOTENCY_KEY_HEADER, key)
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
    }

    private static URI effects(FakeDownstream downstream) {
        return URI.create(downstream.uri() + "/effects");
    }
}
