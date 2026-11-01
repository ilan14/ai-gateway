package com.lava.ai_gateway.protection;

import com.lava.ai_gateway.config.GatewayProperties;
import com.lava.ai_gateway.config.GatewayProperties.ProtectionConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** 多实例共享的模型保护；所有 Redis 操作在阻塞任务线程执行。 */
@Service
public class ModelProtection {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ModelProtection.class);
    private final StringRedisTemplate redis;
    private final ProtectionConfig config;
    private final MeterRegistry meters;
    private final DefaultRedisScript<Long> acquire = script("acquire");
    private final DefaultRedisScript<Long> release = script("release");
    private final DefaultRedisScript<Long> renew = script("renew");
    private final DefaultRedisScript<Long> result = script("result");

    public ModelProtection(StringRedisTemplate redis, GatewayProperties properties, MeterRegistry meters) {
        this.redis = redis;
        this.config = properties.getProtection();
        this.meters = meters;
        config.validate();
    }

    private static DefaultRedisScript<Long> script(String name) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("protection/" + name + ".lua"));
        script.setResultType(Long.class);
        return script;
    }

    private <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(e -> e instanceof ProtectionException ? e : new ProtectionException(503));
    }

    /** 在会话写入之前获取许可；调用结果由 observe 单独统计，隔离存储异常。 */
    public <T> Flux<T> guard(String provider, String model, Supplier<Flux<T>> operation) {
        if (!config.isEnabled()) return Flux.defer(operation);
        String scope = provider + ":" + model;
        ProtectionConfig policy = config.getModels().getOrDefault(scope, config);
        List<String> keys = List.of("gateway:{protection}:" + scope + ":state",
                "gateway:{protection}:" + scope + ":probe", "gateway:{protection}:global:inflight",
                "gateway:{protection}:" + scope + ":inflight", "gateway:{protection}:global:rpm",
                "gateway:{protection}:" + scope + ":rpm", "gateway:{protection}:" + scope + ":calls",
                "gateway:{protection}:" + scope + ":failures",
                "gateway:{protection}:" + provider + ":inflight", "gateway:{protection}:" + provider + ":rpm");
        String id = UUID.randomUUID().toString();
        long lease = Math.max(3, config.getLeaseSeconds()) * 1000L;
        return Flux.usingWhen(blocking(() -> {
            Long code = redis.execute(acquire, keys, id, "" + lease, "" + config.getGlobalConcurrency(),
                    "" + policy.getConcurrency(), "" + config.getGlobalRpm(), "" + policy.getRpm(),
                    "" + config.getProviderConcurrency(), "" + config.getProviderRpm());
            if (code == null || code >= 429) {
                meters.counter("gateway.protection.rejected", "scope", scope).increment();
                throw new ProtectionException(code == null ? 503 : code.intValue());
            }
            if (code == 1) meters.counter("gateway.protection.transitions", "state", "half_open").increment();
            Permit permit = new Permit(keys, id, code == 1, policy);
            return permit;
        }), permit -> {
            // 与业务流同时订阅续租，续租失败立即终止调用，禁止静默超配。
            Flux<T> heartbeat = Flux.interval(Duration.ofMillis(lease / 3))
                    .concatMap(tick -> blocking(() -> {
                        Long renewed = redis.execute(renew, keys, id, "" + lease);
                        if (!Long.valueOf(1).equals(renewed)) throw new ProtectionException(503);
                        return renewed;
                    })).flatMap(ignored -> Flux.<T>empty());
            return Flux.defer(operation).contextWrite(ctx -> ctx.put(Permit.class, permit)).publish(shared -> Flux.merge(shared, heartbeat.takeUntilOther(shared.ignoreElements())));
        }, this::release, (permit, error) -> release(permit), this::release);
    }


    /** 仅包围下游请求，客户端取消不记录失败；[DONE] 后取消视为成功。 */
    public <T> Flux<T> observe(boolean stream, Supplier<Flux<T>> source) {
        if (!config.isEnabled()) return Flux.defer(source);
        return Flux.deferContextual(context -> {
            Permit permit = context.get(Permit.class);
            AtomicBoolean done = new AtomicBoolean();
            return Flux.defer(source).switchIfEmpty(Flux.error(new IllegalStateException("下游响应为空"))).timeout(Duration.ofSeconds(permit.policy().getTimeoutSeconds()))
                    .doOnNext(value -> { if ("[DONE]".equals(value)) done.set(true); })
                    .concatWith(Flux.defer(() -> stream && !done.get()
                            ? Flux.error(new IllegalStateException("下游流缺少 [DONE]")) : Flux.empty()))
                    .materialize().concatMap(signal -> {
                        if (signal.isOnNext()) {
                            return stream && "[DONE]".equals(signal.get())
                                    ? record(permit, 0, cooldown(null, permit.policy())).thenReturn(signal) : Mono.just(signal);
                        }
                        if (stream && done.get()) return Mono.just(signal);
                        Throwable error = signal.getThrowable();
                        int outcome = error == null ? (stream && !done.get() ? 1 : 0) : classify(error);
                        return record(permit, outcome, cooldown(error, permit.policy())).thenReturn(signal);
                    }).<T>dematerialize();
        });
    }

    static int classify(Throwable error) {
        if (error instanceof WebClientResponseException http) {
            if (http.getStatusCode().value() == 429) return 2;
            return http.getStatusCode().is5xxServerError() ? 1 : -1;
        }
        return 1;
    }

    private long cooldown(Throwable error, ProtectionConfig policy) {
        if (error instanceof WebClientResponseException http && http.getStatusCode().value() == 429) {
            String value = http.getHeaders().getFirst("Retry-After");
            if (value != null) {
                try { return Math.max(1000, Long.parseLong(value) * 1000); }
                catch (RuntimeException ignored) {
                    try { return Math.max(1000, Duration.between(java.time.Instant.now(), ZonedDateTime.parse(value,
                            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis()); }
                    catch (RuntimeException invalid) { /* 无效响应头使用配置冷却时间。 */ }
                }
            }
        }
        return policy.getCooldownSeconds() * 1000L;
    }

    private Mono<Void> record(Permit p, int outcome, long cooldown) {
        return blocking(() -> redis.execute(result, p.keys(), p.id(), "" + outcome,
                "" + p.policy().getWindowSeconds() * 1000L, "" + p.policy().getMinimumCalls(),
                "" + p.policy().getFailurePercent(), "" + p.policy().getConsecutiveFailures(), "" + cooldown,
                p.probe() ? "1" : "0")).doOnNext(transition -> {
                    meters.counter("gateway.protection.results", "outcome", "" + outcome).increment();
                    if (transition > 0) meters.counter("gateway.protection.transitions",
                            "state", transition == 2 ? "closed" : "open").increment();
                }).then();
    }

    private Mono<Void> release(Permit p) {
        return blocking(() -> redis.execute(release, p.keys(), p.id()))
                .doOnError(error -> log.warn("释放保护许可失败，等待租约回收", error))
                .onErrorResume(error -> Mono.empty()).then();
    }

    private record Permit(List<String> keys, String id, boolean probe, ProtectionConfig policy) { }
}
