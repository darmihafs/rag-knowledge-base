package com.bishe.kb.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.stream.Collectors;

/**
 * 全局异常处理：把异常收敛成统一 code/message，避免把堆栈暴露给前端。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public Result<Void> handleInvalid(BindException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + msg(f))
                .collect(Collectors.joining("; "));
        return Result.fail(4001, "参数校验失败 -> " + detail);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        return Result.fail(4002, "文件超出上传大小限制");
    }

    /** 请求体不是合法 JSON（常见于 Windows curl 传了 GBK 编码的中文） */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleBadBody(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.fail(4003, "请求体格式错误，请确认为 UTF-8 编码的合法 JSON");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnknown(Exception e) {
        log.error("未捕获异常", e);
        return Result.fail(5000, "服务器内部错误");
    }

    private static String msg(FieldError f) {
        return f.getDefaultMessage() == null ? "非法" : f.getDefaultMessage();
    }
}
