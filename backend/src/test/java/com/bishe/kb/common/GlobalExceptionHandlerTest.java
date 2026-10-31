package com.bishe.kb.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 客户端断开属于正常现象：SSE 响应已提交时不能再写 JSON 错误体，
 * 否则会二次抛 HttpMessageNotWritableException 并刷出误导性的 ERROR 日志。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("SSE 客户端断开：响应已提交时安静收尾，不写任何响应体")
    void disconnectOnCommittedSseResponseShouldNotWriteBody() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setCommitted(true);

        assertThatCode(() -> handler.handleClientDisconnect(
                new IOException("你的主机中的软件中止了一个已建立的连接"), response))
                .doesNotThrowAnyException();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    @DisplayName("路径参数类型不匹配：返回 4004 而不是兜底的 5000")
    void typeMismatchShouldReturnBadRequestInsteadOf500() {
        MethodArgumentTypeMismatchException e = new MethodArgumentTypeMismatchException(
                "abc", Long.class, "id", null, new NumberFormatException("abc"));

        Result<Void> result = handler.handleTypeMismatch(e);

        assertThat(result.getCode()).isEqualTo(4004);
        assertThat(result.getMessage()).contains("id");
    }
}
