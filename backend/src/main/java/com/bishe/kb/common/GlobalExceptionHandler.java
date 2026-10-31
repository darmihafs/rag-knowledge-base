package com.bishe.kb.common;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
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

    /**
     * 客户端主动断开（点「停止生成」、关闭页面）时，Tomcat 会在异步派发阶段抛出
     * “你的主机中的软件中止了一个已建立的连接”这类 IOException
     * （Spring 6.2 也可能包装成 {@code AsyncRequestNotUsableException}，同为 IOException）。
     *
     * <p>此时 SSE 响应已经提交、Content-Type 是 {@code text/event-stream}，
     * 再交给 {@link #handleUnknown} 会尝试写 JSON 而二次抛
     * {@code HttpMessageNotWritableException}，刷出一条误导性的 ERROR 日志。
     * 这里直接安静收尾：客户端已经走了，没有任何可写的响应体。
     */
    @ExceptionHandler(IOException.class)
    public void handleClientDisconnect(IOException e, HttpServletResponse response) {
        if (response.isCommitted()) {
            log.debug("客户端已断开，忽略响应写入异常: {}", e.getMessage());
        } else {
            log.warn("响应写入失败（客户端可能已断开）: {}", e.getMessage());
        }
    }

    /**
     * 路径/查询参数类型转换失败，例如 {@code DELETE /api/sessions/abc} 里的非数字 id。
     *
     * <p>属于调用方请求错误，不该落到 {@link #handleUnknown} 打成 500 并打印整段堆栈；
     * 这里统一回 4004，且不在响应里回显原始输入。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不匹配: {}={}", e.getName(), e.getValue());
        return Result.fail(4004, "请求参数格式错误：" + e.getName() + " 的值不合法");
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
