package com.lava.ai_gateway.controller;

import com.lava.ai_gateway.provider.MockModelProvider;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/mock/settings")
public class MockController {
    private final MockModelProvider provider;

    public MockController(MockModelProvider provider) { this.provider = provider; }

    @GetMapping
    public MockModelProvider.Settings settings() { return provider.settings(); }

    @PutMapping
    public MockModelProvider.Settings update(@RequestBody MockModelProvider.Settings settings) {
        return provider.update(settings);
    }
}
