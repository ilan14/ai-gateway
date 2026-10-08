# Mock Provider

Mock is enabled by default and selected by an explicit `model: "mock"`.
The gateway forwards requests to mock-http-server over HTTP, using
`POST /v1/chat/completions` for both JSON and SSE responses.
It bypasses session preparation, MySQL persistence and Redis access, ignores
`X-Session-Id`, and does not return a session header. Application startup still
loads the database/Redis components.

## Server configuration

```yaml
gateway:
  mock:
    url: ${MOCK_HTTP_SERVER_URL:http://localhost}
    port: ${MOCK_HTTP_SERVER_PORT:8081}
```

`url` specifies the scheme and host. `port` selects the port separately.
The default upstream endpoint is `http://localhost:8081/v1/chat/completions`.
Start mock-http-server before sending mock requests.

```sh
curl http://localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"mock","messages":[{"role":"user","content":"hello"}],"stream":false}'
```

Set `stream` to `true` for SSE. The gateway forwards upstream data frames,
including `[DONE]`, and decodes non-stream JSON as a chat completion.
Response content, token usage, delays and failures are determined by
mock-http-server. Configure scenarios directly on that service; ai-gateway
has no settings state or `/mock/settings` endpoints.

## Metrics

Scrape `/actuator/prometheus`. Gateway metrics retain `provider="mock"`,
`stream` and `outcome` labels. Scenario labels are removed because the gateway
does not manage or fetch upstream settings.

```promql
sum by (outcome) (rate(gateway_mock_requests_total{provider="mock"}[1m]))
histogram_quantile(0.95, sum by (le) (rate(gateway_mock_duration_seconds_bucket[5m])))
histogram_quantile(0.95, sum by (le) (rate(gateway_mock_first_frame_seconds_bucket[5m])))
gateway_mock_inflight{provider="mock"}
```

Provider timings include HTTP communication with the upstream server.
Successful, failed and cancelled calls are counted at termination. Hanging
requests remain in the inflight gauge until cancellation. HTTP failures use
the upstream status as the outcome; other failures use `error`.
