package com.lava.ai_gateway.service;

import com.lava.ai_gateway.entity.SessionEntity;
import com.lava.ai_gateway.repository.MessageRepository;
import com.lava.ai_gateway.repository.SessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionServiceTest {
    @Test
    void requiresExistingSessionWithoutCreatingIt() {
        SessionRepository repository = mock(SessionRepository.class);
        SessionService service = new SessionService(repository, mock(MessageRepository.class));
        assertThatThrownBy(() -> service.requireSession("missing"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(404));
        when(repository.findById("existing")).thenReturn(new SessionEntity());
        assertThatCode(() -> service.requireSession("existing")).doesNotThrowAnyException();
        verify(repository, never()).save(any());
    }
}
