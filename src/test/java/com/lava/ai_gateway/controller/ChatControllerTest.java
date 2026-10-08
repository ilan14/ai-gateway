package com.lava.ai_gateway.controller;

import com.lava.ai_gateway.model.*;
import com.lava.ai_gateway.service.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ChatControllerTest {
    @Test
    void mockSkipsSessionPreparationAndDoesNotReturnSessionHeader() {
        for (boolean stream : List.of(false, true)) {
            ChatService service = mock(ChatService.class);
            ChatRequest request = new ChatRequest("mock", List.of(new Message("user", "hello")), stream, null, null);
            when(service.isMock(request)).thenReturn(true);
            when(service.streamChat(request, null)).thenReturn(Flux.just("[DONE]"));
            when(service.chat(request, null)).thenReturn(Mono.just(new ChatResponse("mock-id", "chat.completion",
                    0, "mock", List.of(), null)));
            MockServerHttpResponse response = new MockServerHttpResponse();
            new ChatController(service, new ObjectMapper()).chatCompletions(request, response, "invalid")
                    .block(Duration.ofSeconds(5));
            assertThat(response.getHeaders().getFirst("X-Session-Id")).isNull();
            verify(service, never()).prepareSessionId(any());
        }
    }

    @Test
    void returnsSessionHeaderForBothResponseModes() {
        for (boolean stream : List.of(false, true)) {
            ChatService service = mock(ChatService.class);
            String id = "8d404d66-22d4-45ea-a3b1-83a2b3a695a9";
            ChatRequest request = new ChatRequest("stub", List.of(new Message("user", "hello")),
                    stream, null, null);
            when(service.prepareSessionId(null)).thenReturn(Mono.just(id));
            when(service.streamChat(request, id)).thenReturn(Flux.just("[DONE]"));
            when(service.chat(request, id)).thenReturn(Mono.just(new ChatResponse("completion-id",
                    "chat.completion", 0, "stub", List.of(), null)));
            MockServerHttpResponse response = new MockServerHttpResponse();

            new ChatController(service, new ObjectMapper()).chatCompletions(request, response, null)
                    .block(Duration.ofSeconds(5));

            assertThat(response.getHeaders().getFirst("X-Session-Id")).isEqualTo(id);
            String body = response.getBodyAsString().block(Duration.ofSeconds(5));
            if (stream) {
                assertThat(body).isEqualTo("data: [DONE]\n\n");
                verify(service).streamChat(request, id);
            } else {
                assertThat(body).contains("completion-id");
                verify(service).chat(request, id);
            }
        }
    }
}
