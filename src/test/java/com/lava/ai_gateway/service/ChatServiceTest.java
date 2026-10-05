package com.lava.ai_gateway.service;

import com.lava.ai_gateway.model.*;
import com.lava.ai_gateway.provider.ModelProvider;
import com.lava.ai_gateway.router.ModelRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ChatServiceTest {
    private final ModelRouter router = mock(ModelRouter.class);
    private final ModelProvider provider = mock(ModelProvider.class);
    private final SessionService sessions = mock(SessionService.class);
    private final ChatService service = new ChatService(router, new ObjectMapper(), sessions);

    @BeforeEach
    void setUp() {
        when(router.route("stub")).thenReturn(provider);
    }

    @Test
    void mockBypassesAllSessionOperationsInBothModes() {
        ChatRequest request = new ChatRequest("mock", List.of(new Message("user", "hello")), false, null, null);
        when(router.route("mock")).thenReturn(provider);
        when(provider.chat(request)).thenReturn(Mono.just(new ChatResponse("mock-id", "chat.completion", 0,
                "mock", List.of(), null)));
        when(provider.streamChat(request)).thenReturn(Flux.just("[DONE]"));
        assertThat(service.chat(request, "invalid-session").block(Duration.ofSeconds(5)).id()).isEqualTo("mock-id");
        assertThat(service.streamChat(request, "invalid-session").collectList().block(Duration.ofSeconds(5)))
                .containsExactly("[DONE]");
        verifyNoInteractions(sessions);
    }

    @Test
    void chatSavesLastUserBeforeCallingProviderAndSavesReply() {
        ChatRequest request = request(false);
        ChatResponse response = new ChatResponse("id", "chat.completion", 0, "stub",
                List.of(new Choice(0, new Message("assistant", "reply"), "stop")), null);
        when(provider.chat(request)).thenAnswer(invocation -> {
            verify(sessions).getOrCreate("8d404d66-22d4-45ea-a3b1-83a2b3a695a9", "stub");
            verify(sessions).appendUserMessage("8d404d66-22d4-45ea-a3b1-83a2b3a695a9", "latest");
            return Mono.just(response);
        });

        assertThat(service.chat(request, "8d404d66-22d4-45ea-a3b1-83a2b3a695a9").block(Duration.ofSeconds(5))).isSameAs(response);
        verify(sessions, timeout(2000)).appendAssistantMessage("8d404d66-22d4-45ea-a3b1-83a2b3a695a9", "reply");
        verify(sessions, never()).appendUserMessage("8d404d66-22d4-45ea-a3b1-83a2b3a695a9", "old");
    }

    @Test
    void streamPassesThroughFramesAndSavesAccumulatedReplyWithGeneratedSessionId() {
        ChatRequest request = request(true);
        String first = "{\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}";
        String second = "{\"choices\":[{\"delta\":{\"content\":\" world\"}}]}";
        when(provider.streamChat(request)).thenReturn(Flux.just(first, second, "[DONE]"));

        assertThat(service.streamChat(request, " ").collectList().block(Duration.ofSeconds(5)))
                .containsExactly(first, second, "[DONE]");
        var sessionId = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(sessions).getOrCreate(sessionId.capture(), eq("stub"));
        assertThat(java.util.UUID.fromString(sessionId.getValue())).isNotNull();
        verify(sessions).appendUserMessage(sessionId.getValue(), "latest");
        verify(sessions, timeout(2000)).appendAssistantMessage(sessionId.getValue(), "hello world");
    }

    @Test
    void persistenceFailurePreventsProviderCall() {
        doThrow(new IllegalStateException("database unavailable"))
                .when(sessions).appendUserMessage("8d404d66-22d4-45ea-a3b1-83a2b3a695a9", "latest");

        assertThatThrownBy(() -> service.chat(request(false), "8d404d66-22d4-45ea-a3b1-83a2b3a695a9").block(Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class).hasMessage("database unavailable");
        verifyNoInteractions(provider);
        verify(sessions, never()).appendAssistantMessage(anyString(), anyString());
    }

    @Test
    void failedStreamPropagatesErrorWithoutSavingPartialReply() {
        ChatRequest request = request(true);
        when(provider.streamChat(request)).thenReturn(Flux.concat(
                Flux.just("{\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}"),
                Flux.error(new IllegalStateException("upstream failed"))));

        assertThatThrownBy(() -> service.streamChat(request, "8d404d66-22d4-45ea-a3b1-83a2b3a695a9")
                .collectList().block(Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class).hasMessage("upstream failed");
        verify(sessions, never()).appendAssistantMessage(anyString(), anyString());
    }

    @Test
    void prepareGeneratesIdWhenAbsent() {
        String id = service.prepareSessionId(null).block(Duration.ofSeconds(5));
        assertThat(java.util.UUID.fromString(id).toString()).isEqualTo(id);
        verifyNoInteractions(sessions);
    }

    @Test
    void prepareChecksExistingSession() {
        String id = "8d404d66-22d4-45ea-a3b1-83a2b3a695a9";
        assertThat(service.prepareSessionId(id).block(Duration.ofSeconds(5))).isEqualTo(id);
        verify(sessions).requireSession(id);
    }

    @Test
    void prepareRejectsMalformedAndShortenedUuids() {
        for (String id : List.of("invalid", "1-1-1-1-1", " 8d404d66-22d4-45ea-a3b1-83a2b3a695a9")) {
            assertThatThrownBy(() -> service.prepareSessionId(id).block(Duration.ofSeconds(5)))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .satisfies(error -> assertThat(((org.springframework.web.server.ResponseStatusException) error)
                            .getStatusCode().value()).isEqualTo(400));
        }
        verifyNoInteractions(sessions, provider);
    }

    @Test
    void serverContextLoadsHistoryBeforeSavingInputInBothModes() {
        String id = "8d404d66-22d4-45ea-a3b1-83a2b3a695a9";
        var history = List.of(new Message("user", "old"), new Message("assistant", "previous"));
        when(sessions.loadMessages(id)).thenAnswer(invocation -> {
            verify(sessions, never()).appendUserMessage(id, "latest");
            return history;
        });
        for (boolean stream : List.of(false, true)) {
            clearInvocations(sessions);
            ChatRequest incoming = new ChatRequest("stub", List.of(new Message("user", "latest")),
                    stream, 0.5, 100, "server");
            ChatRequest expected = new ChatRequest("stub", List.of(new Message("user", "old"),
                    new Message("assistant", "previous"), new Message("user", "latest")), stream, 0.5, 100);
            when(provider.chat(expected)).thenReturn(Mono.just(new ChatResponse("id", "chat.completion", 0,
                    "stub", List.of(new Choice(0, new Message("assistant", "reply"), "stop")), null)));
            when(provider.streamChat(expected)).thenReturn(Flux.just(
                    "{\"choices\":[{\"delta\":{\"content\":\"reply\"}}]}", "[DONE]"));
            if (stream) {
                service.streamChat(incoming, id).doOnNext(chunk -> {
                    if ("[DONE]".equals(chunk)) verify(sessions).appendAssistantMessage(id, "reply");
                }).collectList().block(Duration.ofSeconds(5));
                verify(provider).streamChat(expected);
            } else {
                service.chat(incoming, id).block(Duration.ofSeconds(5));
                verify(provider).chat(expected);
            }
            verify(sessions).appendUserMessage(id, "latest");
            verify(sessions).appendAssistantMessage(id, "reply");
        }
    }

    @Test
    void invalidContextModeAndServerMessagesAreRejectedBeforeStorage() {
        for (ChatRequest invalid : List.of(
                new ChatRequest("stub", List.of(new Message("user", "hello")), false, null, null, "invalid"),
                new ChatRequest("stub", List.of(), false, null, null, "server"),
                new ChatRequest("stub", List.of(new Message("assistant", "hello")), false, null, null, "server"),
                new ChatRequest("stub", List.of(new Message("user", " ")), false, null, null, "server"),
                new ChatRequest("stub", request(false).messages(), false, null, null, "server"))) {
            assertThatThrownBy(() -> new com.lava.ai_gateway.controller.ChatController(service, new ObjectMapper())
                    .chatCompletions(invalid, new org.springframework.mock.http.server.reactive.MockServerHttpResponse(), null))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
        verifyNoInteractions(sessions, provider);
    }

    @Test
    void clientContextDoesNotReadHistoryOrForwardExtension() {
        ChatRequest incoming = new ChatRequest("stub", request(false).messages(), false, null, null, "client");
        ChatRequest expected = request(false);
        when(provider.chat(expected)).thenReturn(Mono.just(new ChatResponse("id", "chat.completion", 0,
                "stub", List.of(), null)));
        service.chat(incoming, null).block(Duration.ofSeconds(5));
        verify(provider).chat(expected);
        verify(sessions, never()).loadMessages(anyString());
        assertThat(new ObjectMapper().writeValueAsString(expected)).doesNotContain("context_mode");
    }

    @Test
    void incompleteAndErrorStreamsDoNotSaveAssistant() {
        ChatRequest request = request(true);
        for (String frame : List.of("{\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}",
                "{\"error\":{\"message\":\"upstream error\"}}", "invalid")) {
            when(provider.streamChat(request)).thenReturn(Flux.just(frame));
            assertThatThrownBy(() -> service.streamChat(request, null).collectList().block(Duration.ofSeconds(5)))
                    .isInstanceOf(IllegalStateException.class);
        }
        verify(sessions, never()).appendAssistantMessage(anyString(), anyString());
    }

    @Test
    void assistantPersistenceFailurePreventsDoneFrame() {
        ChatRequest request = request(true);
        String id = "8d404d66-22d4-45ea-a3b1-83a2b3a695a9";
        when(provider.streamChat(request)).thenReturn(Flux.just(
                "{\"choices\":[{\"delta\":{\"content\":\"reply\"}}]}", "[DONE]"));
        doThrow(new IllegalStateException("save failed")).when(sessions).appendAssistantMessage(id, "reply");
        var frames = new java.util.ArrayList<String>();
        assertThatThrownBy(() -> service.streamChat(request, id).doOnNext(frames::add)
                .collectList().block(Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class).hasMessage("save failed");
        assertThat(frames).doesNotContain("[DONE]");
    }

    private ChatRequest request(boolean stream) {
        return new ChatRequest("stub", List.of(new Message("user", "old"),
                new Message("assistant", "previous"), new Message("user", "latest")), stream, null, null);
    }
}
