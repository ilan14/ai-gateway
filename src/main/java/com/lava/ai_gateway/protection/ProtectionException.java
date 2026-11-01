package com.lava.ai_gateway.protection;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 网关保护拒绝，供统一错误响应使用。 */
public class ProtectionException extends ResponseStatusException {
    public ProtectionException(int status) {
        super(HttpStatus.valueOf(status), status == 429 ? "请求超过限流额度" : "模型保护暂时不可用");
    }
}
