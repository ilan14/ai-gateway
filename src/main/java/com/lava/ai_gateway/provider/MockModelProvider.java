package com.lava.ai_gateway.provider;

import com.lava.ai_gateway.model.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 进程内模型模拟器：
 * 通过异步延迟、异常和挂起模拟上游行为，不发起外部请求。
 * 会话和存储的绕过由 ChatService 负责；
 * 本类只生成响应并记录 Mock 指标。
 */
@Component
public class MockModelProvider implements ModelProvider {
    /**
     * NORMAL/SLOW 均按配置延迟后成功；
     * ERROR 按概率失败；
     * HANG 一直等待客户端取消。
     * STREAM_ERROR/STREAM_STALL 仅影响流式调用，分别模拟输出部分帧后报错或停顿。
     */
    public enum Scenario {
        NORMAL, SLOW, ERROR, HANG, STREAM_ERROR, STREAM_STALL
    }

    /**
     * 不可变场景配置，每次请求订阅时读取快照，运行中的请求不受后续切换影响。
     *
     * @param scenario 故障场景；SLOW 需配合较大的 delayMs，不额外增加固定延迟
     * @param delayMs 所有场景的基础等待时间，单位毫秒，范围 0..60000
     * @param jitterMs 基础等待之外的随机延迟上限，随机值含两端，范围 0..60000 毫秒
     * @param frameIntervalMs 流式内容帧之间的间隔，首帧不额外等待，范围 0..10000 毫秒
     * @param frames 正常流式响应的内容帧数，不含结束帧和 [DONE]，范围 1..1000
     * @param faultAfterFrames 流式异常或停顿前输出的内容帧数，范围 0..frames
     * @param errorStatus 模拟错误的状态码，仅支持 429、500、503；流已提交后不能修改 HTTP 状态
     * @param failureRate ERROR 场景每次调用的失败概率，范围 0..1，不影响其他场景
     * @param content 非流式回复内容及每个流式内容帧的内容，最多 4096 个 Java 字符
     */
    public record Settings(Scenario scenario, long delayMs, long jitterMs,
                           long frameIntervalMs, int frames, int faultAfterFrames,
                           int errorStatus, double failureRate, String content) {
        public Settings {
            // 在配置创建时限制等待时间和响应规模，防止无界配置进入请求处理链。
            if (scenario == null
                    || delayMs < 0 || delayMs > 60000
                    || jitterMs < 0 || jitterMs > 60000
                    || frameIntervalMs < 0 || frameIntervalMs > 10000
                    || frames < 1 || frames > 1000
                    || faultAfterFrames < 0 || faultAfterFrames > frames
                    || (errorStatus != 429 && errorStatus != 500 && errorStatus != 503)
                    || !Double.isFinite(failureRate) || failureRate < 0 || failureRate > 1
                    || content == null || content.length() > 4096) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid mock settings");
            }
        }
    }

    private volatile Settings settings = new Settings(Scenario.NORMAL, 0, 0, 20, 5, 2, 503, 1, "ok");
    /** 将响应对象编码为 OpenAI 兼容的 SSE data 内容，不含 data: 前缀。 */
    private final ObjectMapper mapper;
    /** 注册 Mock 专用的计数、耗时和在途指标。 */
    private final MeterRegistry registry;
    /** 已订阅且尚未完成、失败或取消的 Provider 调用数。 */
    private final AtomicInteger inFlight = new AtomicInteger();

    /**
     * @param mapper 项目统一的 JSON 序列化器
     * @param registry 项目统一的指标注册表，由已有 Prometheus 端点导出指标
     */
    public MockModelProvider(ObjectMapper mapper, MeterRegistry registry) {
        this.mapper = mapper;
        this.registry = registry;
        registry.gauge("gateway.mock.inflight", List.of(io.micrometer.core.instrument.Tag.of("provider", "mock")),
                inFlight);
    }

    public Settings settings() {
        return settings;
    }

    public Settings update(Settings value) {
        settings = value;
        return value;
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public boolean supportsModel(String model) {
        return "mock".equals(model);
    }

    @Override
    public Mono<ChatResponse> chat(ChatRequest request) {
        // 按订阅开始计时和计数；request 保持统一 Provider 接口，内容不参与模拟结果生成。
        return Mono.defer(() -> {
            Settings snapshot = settings;
            Timer.Sample sample = Timer.start(registry);
            inFlight.incrementAndGet();
            // 先模拟等待，再决定挂起、报错或成功；不包含超时、重试等保护逻辑。
            Mono<ChatResponse> result = pause(snapshot).then(Mono.defer(() -> {
                if (snapshot.scenario() == Scenario.HANG) {
                    return Mono.never();
                }

                if (fails(snapshot)) {
                    return Mono.error(error(snapshot));
                }

                // usage 为合成值：输入固定 1 token，输出按 Java 字符数计算，并非真实分词结果。
                return Mono.just(new ChatResponse(id(), "chat.completion", now(), "mock",
                        List.of(new Choice(0, new Message("assistant", snapshot.content()), "stop")),
                        new Usage(1, snapshot.content().length(), 1 + snapshot.content().length())));
            }));
            return observe(result.flux(), snapshot, false, sample).single();
        });
    }

    @Override
    public Flux<String> streamChat(ChatRequest request) {
        // 每次订阅使用独立快照和响应 ID；同一流中的所有帧共享 ID 和创建时间。
        return Flux.defer(() -> {
            Settings snapshot = settings;
            Timer.Sample sample = Timer.start(registry);
            inFlight.incrementAndGet();
            String id = id();
            long created = now();
            Flux<String> result = pause(snapshot).thenMany(Flux.defer(() -> {
                if (snapshot.scenario() == Scenario.HANG) {
                    return Flux.never();
                }

                if (fails(snapshot)) {
                    return Flux.error(error(snapshot));
                }

                // 故障流只生成故障点之前的帧；concatMap 保证帧顺序和帧间等待串行执行。
                boolean fault =
                        snapshot.scenario() == Scenario.STREAM_ERROR || snapshot.scenario() == Scenario.STREAM_STALL;
                int count = fault ? snapshot.faultAfterFrames() : snapshot.frames();
                Flux<String> chunks = Flux.range(0, count).concatMap(index ->
                        Mono.delay(Duration.ofMillis(index == 0 ? 0 : snapshot.frameIntervalMs()))
                                .map(ignored -> chunk(id, created, snapshot.content(), null)));
                // 故障流不发送正常结束标记；停顿流一直保留订阅，直到客户端取消。
                if (snapshot.scenario() == Scenario.STREAM_ERROR) {
                    return chunks.concatWith(Flux.error(error(snapshot)));
                }
                if (snapshot.scenario() == Scenario.STREAM_STALL) {
                    return chunks.concatWith(Flux.never());
                }
                return chunks.concatWithValues(chunk(id, created, null, "stop"), "[DONE]");
            }));
            return observe(result, snapshot, true, sample);
        });
    }

    /**
     * 为单次订阅记录指标，成功、失败、取消均在终止时结算。
     *
     * @param source 待观测的响应流，非流式响应也转换为 Flux 复用此逻辑
     * @param snapshot 本次请求配置，用于固定指标的场景标签
     * @param stream 是否为流式调用，仅流式调用记录首帧耗时
     * @param sample 订阅开始时创建的计时样本，包含模拟等待时间
     */
    private <T> Flux<T> observe(Flux<T> source, Settings snapshot, boolean stream, Timer.Sample sample) {
        AtomicBoolean first = new AtomicBoolean();
        String[] outcome = {"success"};
        return source.doOnNext(value -> {
                    // 首帧只记录一次；同一样本可用于测量从订阅起算的首帧和总耗时。
                    if (stream && first.compareAndSet(false, true))
                        sample.stop(timer("gateway.mock.first.frame", snapshot, true, "received"));
                }).doOnError(error -> outcome[0] = error instanceof ResponseStatusException status
                        ? Integer.toString(status.getStatusCode().value()) : "error")
                .doFinally(signal -> {
                    // never() 不会自行终止，但取消也会执行此处，释放在途计数并记录等待耗时。
                    String result = signal == reactor.core.publisher.SignalType.CANCEL ? "cancelled" : outcome[0];
                    inFlight.decrementAndGet();
                    sample.stop(timer("gateway.mock.duration", snapshot, stream, result));
                    registry.counter("gateway.mock.requests", "provider", "mock", "scenario",
                            snapshot.scenario().name(),
                            "stream", Boolean.toString(stream), "outcome", result).increment();
                });
    }

    /** 使用有限的场景、模式和结果标签，并导出直方图供 Grafana 计算分位数。 */
    private Timer timer(String name, Settings snapshot, boolean stream, String outcome) {
        return Timer.builder(name).tags("provider", "mock", "scenario", snapshot.scenario().name(),
                "stream", Boolean.toString(stream), "outcome", outcome).publishPercentileHistogram().register(registry);
    }

    /** 异步等待基础延迟加随机抖动，不阻塞 WebFlux 请求线程。 */
    private Mono<Long> pause(Settings snapshot) {
        long jitter = snapshot.jitterMs() == 0 ? 0 : ThreadLocalRandom.current().nextLong(snapshot.jitterMs() + 1);
        return Mono.delay(Duration.ofMillis(snapshot.delayMs() + jitter));
    }

    /** 仅 ERROR 场景抽样，failureRate=0 始终成功，failureRate=1 始终失败。 */
    private boolean fails(Settings snapshot) {
        return snapshot.scenario() == Scenario.ERROR && ThreadLocalRandom.current().nextDouble() < snapshot.failureRate();
    }

    /** 生成模拟上游异常；这是进程内失败信号，并未实际收到 HTTP 响应。 */
    private ResponseStatusException error(Settings snapshot) {
        return new ResponseStatusException(HttpStatus.valueOf(snapshot.errorStatus()), "Simulated mock upstream " +
                "failure");
    }

    /**
     * @param id 本次流式响应的统一标识
     * @param created 响应创建时间，Unix 秒
     * @param content 内容帧的文本；正常结束帧为 null
     * @param finish 内容帧为 null；正常结束帧为 stop
     */
    private String chunk(String id, long created, String content, String finish) {
        try {
            return mapper.writeValueAsString(new StreamChunk(id, "chat.completion.chunk", created, "mock",
                    List.of(new StreamChoice(0, new Delta(null, content), finish))));
        } catch (Exception error) {
            throw new IllegalStateException("Cannot serialize mock chunk", error);
        }
    }

    private String id() {
        return "mock-" + UUID.randomUUID();
    }

    private long now() {
        return System.currentTimeMillis() / 1000;
    }
}
