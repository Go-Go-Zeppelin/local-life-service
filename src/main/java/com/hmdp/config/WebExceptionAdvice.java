package com.hmdp.config;

import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result> handleStatusException(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatus()).body(Result.fail(e.getReason()));
    }

    // 全局异常处理捕捉，记录通用日志，返回给前端通用响应 Result.fail("服务器异常")
    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("服务器异常:" + e.getMessage());
    }
}
