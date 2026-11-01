package com.lava.ai_gateway.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProtectionConfigTest {
    @Test
    void invalidThresholdAndLeaseAreRejected() {
        var config = new GatewayProperties.ProtectionConfig();
        config.setFailurePercent(101);
        assertThatThrownBy(config::validate).isInstanceOf(IllegalArgumentException.class);
        config.setFailurePercent(50);
        config.setLeaseSeconds(0);
        assertThatThrownBy(config::validate).isInstanceOf(IllegalArgumentException.class);
    }
}
