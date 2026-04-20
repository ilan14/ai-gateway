package com.lava.ai_gateway.controller;

import com.lava.ai_gateway.dto.SessionDTO;
import com.lava.ai_gateway.dto.SessionDetailDTO;
import com.lava.ai_gateway.dto.UpdateTitleRequest;
import com.lava.ai_gateway.service.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@Slf4j
@Tag(name = "Session", description = "会话管理接口")
@RestController
@RequestMapping("/v1/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Operation(summary = "列出所有会话", description = "按最近活跃时间倒序返回")
    @GetMapping
    public Mono<List<SessionDTO>> listSessions() {
        return Mono.fromCallable(sessionService::listSessions)
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "获取会话详情", description = "返回会话信息及完整消息历史")
    @GetMapping("/{sessionId}")
    public Mono<ResponseEntity<SessionDetailDTO>> getSession(@PathVariable String sessionId) {
        return Mono.fromCallable(() -> sessionService.getSessionDetail(sessionId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(opt -> opt.map(ResponseEntity::ok)
                        .orElse(ResponseEntity.notFound().build()));
    }

    @Operation(summary = "修改会话标题")
    @PutMapping("/{sessionId}/title")
    public Mono<ResponseEntity<Void>> updateTitle(@PathVariable String sessionId,
                                                  @RequestBody UpdateTitleRequest request) {
        log.info("PUT /v1/sessions/{}/title title={}", sessionId, request.title());
        return Mono.fromCallable(() -> sessionService.updateTitle(sessionId, request.title()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(found -> found
                        ? ResponseEntity.<Void>noContent().build()
                        : ResponseEntity.<Void>notFound().build());
    }

    @Operation(summary = "删除会话", description = "同时删除该会话的所有消息及 Redis 缓存")
    @DeleteMapping("/{sessionId}")
    public Mono<ResponseEntity<Void>> deleteSession(@PathVariable String sessionId) {
        log.info("DELETE /v1/sessions/{}", sessionId);
        return Mono.fromCallable(() -> sessionService.deleteSession(sessionId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(found -> found
                        ? ResponseEntity.<Void>noContent().build()
                        : ResponseEntity.<Void>notFound().build());
    }
}
