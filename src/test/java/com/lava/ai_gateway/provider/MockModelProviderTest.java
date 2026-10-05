package com.lava.ai_gateway.provider;

import com.lava.ai_gateway.model.ChatRequest;
import com.lava.ai_gateway.model.Message;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static com.lava.ai_gateway.provider.MockModelProvider.Scenario.*;

class MockModelProviderTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MockModelProvider provider = new MockModelProvider(new ObjectMapper(), registry);
    private final ChatRequest request = new ChatRequest("mock", List.of(new Message("user", "hello")), false, null, null);

    @Test
    void normalResponsesAreCompatibleAndRecordMetrics() {
        assertThat(provider.chat(request).block(Duration.ofSeconds(2)).choices().get(0).message().content()).isEqualTo("ok");
        var chunks = provider.streamChat(request).collectList().block(Duration.ofSeconds(2));
        assertThat(chunks).hasSize(7).last().isEqualTo("[DONE]");
        assertThat(registry.get("gateway.mock.inflight").gauge().value()).isZero();
        assertThat(registry.get("gateway.mock.requests").tag("stream", "false").counter().count()).isEqualTo(1);
        assertThat(registry.get("gateway.mock.first.frame").timer().count()).isEqualTo(1);
    }

    @Test
    void upstreamFailureHasConfiguredStatusAndOutcome() {
        provider.update(settings(ERROR, 429));
        assertThatThrownBy(() -> provider.chat(request).block(Duration.ofSeconds(2)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(429));
        assertThat(registry.get("gateway.mock.requests").tag("outcome", "429").counter().count()).isEqualTo(1);
        assertThat(registry.get("gateway.mock.inflight").gauge().value()).isZero();
    }

    @Test
    void hangingRequestKeepsSnapshotAndReleasesMetricsOnCancel() {
        provider.update(settings(HANG, 503));
        var subscription = provider.chat(request).subscribe();
        assertThat(registry.get("gateway.mock.inflight").gauge().value()).isEqualTo(1);
        provider.update(settings(NORMAL, 503));
        subscription.dispose();
        assertThat(registry.get("gateway.mock.inflight").gauge().value()).isZero();
        assertThat(registry.get("gateway.mock.requests").tag("scenario", "HANG").tag("outcome", "cancelled")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void streamFailsAfterConfiguredFramesWithoutDone() {
        provider.update(settings(STREAM_ERROR, 503));
        List<String> frames = new CopyOnWriteArrayList<>();
        assertThatThrownBy(() -> provider.streamChat(request).doOnNext(frames::add).collectList().block(Duration.ofSeconds(2)))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(frames).hasSize(2).doesNotContain("[DONE]");
    }

    @Test
    void delayedRequestRetainsSettingsWhenScenarioChanges() throws Exception {
        provider.update(new MockModelProvider.Settings(SLOW, 50, 0, 0, 5, 2, 503, 1, "old"));
        var result = provider.chat(request).toFuture();
        provider.update(settings(ERROR, 429));
        assertThat(result.get(2, TimeUnit.SECONDS).choices().get(0).message().content()).isEqualTo("old");
    }

    @Test
    void stalledStreamEmitsOnlyConfiguredFramesAndCanBeCancelled() throws Exception {
        provider.update(settings(STREAM_STALL, 503));
        CountDownLatch received = new CountDownLatch(2);
        List<String> frames = new CopyOnWriteArrayList<>();
        var subscription = provider.streamChat(request).subscribe(frame -> {
            frames.add(frame);
            received.countDown();
        });
        try {
            assertThat(received.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(frames).hasSize(2).doesNotContain("[DONE]");
            assertThat(registry.get("gateway.mock.inflight").gauge().value()).isEqualTo(1);
        } finally {
            subscription.dispose();
        }
        assertThat(registry.get("gateway.mock.inflight").gauge().value()).isZero();
    }

    @Test
    void rejectsUnboundedSettings() {
        assertThatThrownBy(() -> new MockModelProvider.Settings(NORMAL, 60001, 0, 0, 1, 0, 503, 1, "ok"))
                .isInstanceOf(ResponseStatusException.class);
    }

    private MockModelProvider.Settings settings(MockModelProvider.Scenario scenario, int status) {
        return new MockModelProvider.Settings(scenario, 0, 0, 0, 5, 2, status, 1, "ok");
    }
}
