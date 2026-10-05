# Mock Provider

Mock is enabled by default and selected only by an explicit `model: "mock"`.
It bypasses session preparation, MySQL persistence and Redis access, ignores
`X-Session-Id`, and does not return a session header. Other models keep their
existing behavior. Application startup still loads the database/Redis components.

## Calling the mock

```sh
curl http://localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"mock","messages":[{"role":"user","content":"hello"}],"stream":false}'
```

Set `stream` to `true` for SSE. Normal streams end with a finish chunk and `[DONE]`.
Token usage in non-stream responses is synthetic: one prompt token and one
completion token per Java string character. It is not a tokenizer estimate.

For a quick batch of 1000 requests with concurrency 32 (each output line is the
HTTP status and total request time), run:

```sh
seq 1 1000 | xargs -P 32 -I '{}' curl -sS -o /dev/null --max-time 10 \
  -w '%{http_code} %{time_total}\n' \
  http://localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"mock","messages":[{"role":"user","content":"hello"}],"stream":false}'
```

This is a simple fixed-concurrency smoke load, not a constant arrival-rate benchmark.

## Changing scenarios

`GET /mock/settings` returns the active settings. `PUT /mock/settings` replaces
all settings atomically. Each request takes a snapshot when subscribed; changes
apply to subsequent requests. Settings are per process and reset on restart.
The management endpoint has no built-in authentication; restrict its ingress
to trusted test operators when sharing a deployment.

```sh
curl -X PUT http://localhost:8080/mock/settings \
  -H 'Content-Type: application/json' \
  -d '{"scenario":"SLOW","delayMs":3000,"jitterMs":500,"frameIntervalMs":100,"frames":10,"faultAfterFrames":3,"errorStatus":503,"failureRate":1,"content":"ok"}'
```

| Scenario | Behavior |
| --- | --- |
| NORMAL | Successful completion after configured delay |
| SLOW | Successful completion after configured delay; use a larger delayMs |
| ERROR | Fails with errorStatus at failureRate probability; otherwise succeeds |
| HANG | Never emits a result; client must cancel |
| STREAM_ERROR | Emits faultAfterFrames content frames, then fails |
| STREAM_STALL | Emits faultAfterFrames content frames, then waits for cancellation |

STREAM_ERROR and STREAM_STALL affect streaming requests only; non-stream requests
succeed normally. delayMs and a random value from zero to jitterMs apply before
the first result in all scenarios. frameIntervalMs applies between content frames.
Each frame contains content. Fault streams do not emit a finish chunk or `[DONE]`.
After SSE is committed, a stream failure cannot change the HTTP status; clients
observe an interrupted response instead. No timeout, retry, rate limiter or
circuit breaker is implemented.

Bounds: delayMs/jitterMs 0..60000; frameIntervalMs 0..10000; frames 1..1000;
faultAfterFrames 0..frames; errorStatus 429/500/503; failureRate 0..1;
content at most 4096 Java string characters.

## Prometheus and Grafana

Scrape `/actuator/prometheus`. Mock metrics use `provider="mock"`, with bounded
scenario, stream and outcome labels. Completed, failed and cancelled requests
are counted at termination; still-hanging requests appear in the inflight gauge.

```promql
# Completed/failed/cancelled requests per second
sum by (scenario, outcome) (rate(gateway_mock_requests_total{provider="mock"}[1m]))

# P95 provider duration, in seconds (includes hanging time before cancellation)
histogram_quantile(0.95, sum by (le, scenario) (rate(gateway_mock_duration_seconds_bucket[5m])))

# P95 first SSE frame latency, in seconds
histogram_quantile(0.95, sum by (le, scenario) (rate(gateway_mock_first_frame_seconds_bucket[5m])))

# Currently active provider calls
gateway_mock_inflight{provider="mock"}
```

These are provider metrics, excluding HTTP serialization and response transmission.
Existing aggregate HTTP/JVM metrics still include Mock traffic and do not gain a
provider label automatically. Use the dedicated Mock series for scenario analysis.
Run load generation separately against the normal chat endpoint; configure a client
timeout for HANG/STREAM_STALL and monitor JVM resources alongside these panels.
