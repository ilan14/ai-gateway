package com.lava.ai_gateway.router;

import com.lava.ai_gateway.config.GatewayProperties;
import com.lava.ai_gateway.provider.ModelProvider;
import com.lava.ai_gateway.provider.StubModelProvider;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelRouterTest {
    @Test
    void resolvesDefaultAndUnknownModelsConsistently() {
        var properties = new GatewayProperties();
        properties.setDefaultModel("real");
        var provider = mock(ModelProvider.class);
        when(provider.supportsModel("real")).thenReturn(true);
        var router = new ModelRouter(List.of(provider), properties, mock(StubModelProvider.class));
        assertThat(router.resolveModel(null)).isEqualTo("real");
        assertThat(router.resolveModel(" ")).isEqualTo("real");
        assertThat(router.resolveModel("unknown")).isEqualTo("stub");
        assertThat(router.route(router.resolveModel(null))).isSameAs(provider);
    }
}
