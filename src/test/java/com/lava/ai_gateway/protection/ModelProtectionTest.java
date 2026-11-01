package com.lava.ai_gateway.protection;

import com.lava.ai_gateway.config.GatewayProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ModelProtectionTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final GatewayProperties properties = new GatewayProperties();
    private ModelProtection service() {
        return new ModelProtection(redis, properties, new SimpleMeterRegistry());
    }

    @Test
    void disabledDoesNotUseRedis() {
        assertThat(service().guard("p", "m", () -> Flux.just("ok")).blockLast()).isEqualTo("ok");
        verifyNoInteractions(redis);
    }

    @Test
    void rejectionPreventsBusinessSideEffects() {
        properties.getProtection().setEnabled(true);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(429L);
        AtomicBoolean called = new AtomicBoolean();
        assertThatThrownBy(() -> service().guard("p", "m", () -> {
            called.set(true);
            return Flux.just("bad");
        }).blockLast(Duration.ofSeconds(5))).isInstanceOf(ProtectionException.class);
        assertThat(called).isFalse();
    }

    @Test
    void doneIsRecordedBeforeDownstreamCancelsAndPermitIsReleased() {
        properties.getProtection().setEnabled(true);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);
        ModelProtection protection = service();
        assertThat(protection.guard("p", "m", () -> protection.observe(true,
                () -> Flux.just("frame", "[DONE]"))).takeUntil("[DONE]"::equals)
                .collectList().block(Duration.ofSeconds(5))).containsExactly("frame", "[DONE]");
        verify(redis, timeout(2000).times(3)).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void redisFailureRejectsBeforeBusinessCall() {
        properties.getProtection().setEnabled(true);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> service().guard("p", "m", Flux::empty).blockLast(Duration.ofSeconds(5)))
                .isInstanceOf(ProtectionException.class);
    }

    @Test
    void clientErrorsAreExcludedAnd429HasSeparateOutcome() {
        assertThat(ModelProtection.classify(WebClientResponseException.create(400, "bad", null, null, null))).isEqualTo(-1);
        assertThat(ModelProtection.classify(WebClientResponseException.create(429, "limited", null, null, null))).isEqualTo(2);
        assertThat(ModelProtection.classify(WebClientResponseException.create(503, "down", null, null, null))).isEqualTo(1);
    }

    @Test
    void missingDoneIsFailureAndStillReleasesPermit() {
        properties.getProtection().setEnabled(true);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);
        ModelProtection protection = service();
        assertThatThrownBy(() -> protection.guard("p", "m", () -> protection.observe(true,
                () -> Flux.just("frame"))).blockLast(Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("[DONE]");
        verify(redis, times(3)).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void cancelReleasesWithoutRecordingFailure() {
        properties.getProtection().setEnabled(true);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);
        ModelProtection protection = service();
        protection.guard("p", "m", () -> protection.observe(true,
                () -> Flux.concat(Flux.just("frame"), Flux.never())))
                .take(1).blockLast(Duration.ofSeconds(5));
        verify(redis, timeout(2000).times(2)).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void expiredLeaseAbortsRunningCall() {
        properties.getProtection().setEnabled(true);
        properties.getProtection().setLeaseSeconds(3);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);
        ModelProtection protection = service();
        assertThatThrownBy(() -> protection.guard("p", "m", Flux::never).blockLast(Duration.ofSeconds(5)))
                .isInstanceOf(ProtectionException.class);
        verify(redis, times(3)).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }
}
