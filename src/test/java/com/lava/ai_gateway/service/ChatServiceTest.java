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
    void chatSavesLastUserBeforeCallingProviderAndSavesReply() {
        ChatRequest request = request(false);
        ChatResponse response = new ChatResponse("id", "chat.completion", 0, "stub",
                List.of(new Choice(0, new Message("assistant", "reply"), "stop")), null);
        when(provider.chat(request)).thenAnswer(invocation -> {
            verify(sessions).getOrCreate("session", "stub");
            verify(sessions).appendUserMessage("session", "latest");
            return Mono.just(response);
        });

        assertThat(service.chat(request, "session").block(Duration.ofSeconds(5))).isSameAs(response);
        verify(sessions, timeout(2000)).appendAssistantMessage("session", "reply");
        verify(sessions, never()).appendUserMessage("session", "old");
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
                .when(sessions).appendUserMessage("session", "latest");

        assertThatThrownBy(() -> service.chat(request(false), "session").block(Duration.ofSeconds(5)))
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

        assertThatThrownBy(() -> service.streamChat(request, "session")
                .collectList().block(Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class).hasMessage("upstream failed");
        verify(sessions, never()).appendAssistantMessage(anyString(), anyString());
    }

    private ChatRequest request(boolean stream) {
        return new ChatRequest("stub", List.of(new Message("user", "old"),
                new Message("assistant", "previous"), new Message("user", "latest")), stream, null, null);
    }
}
