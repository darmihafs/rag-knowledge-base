package com.bishe.kb.service.impl;

import com.bishe.kb.common.BizException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Set;

/** JSON schema 只供模型理解，真正的参数边界由这里强制执行。 */
final class AgentToolArguments {
    private final JsonNode value;

    AgentToolArguments(ObjectMapper mapper, String raw, Set<String> allowed) {
        if (raw == null || raw.length() > 2000) {
            throw new BizException(4001, "工具参数为空或超过 2000 字符");
        }
        try {
            value = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
        } catch (JsonProcessingException e) {
            throw new BizException(4001, "工具参数必须为合法 JSON 对象");
        }
        if (value == null || !value.isObject()) {
            throw new BizException(4001, "工具参数必须为 JSON 对象");
        }
        value.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) {
                throw new BizException(4001, "工具包含不支持的参数");
            }
        });
    }

    String text(String name, int max, boolean required) {
        JsonNode node = value.get(name);
        if (node == null && !required) {
            return "";
        }
        if (node == null || !node.isTextual() || node.textValue().length() > max
                || (required && node.textValue().isBlank())) {
            throw new BizException(4001, "文本参数非法: " + name);
        }
        return node.textValue().trim();
    }

    long number(String name, Long fallback, long min, long max) {
        JsonNode node = value.get(name);
        if (node == null && fallback != null) {
            return fallback;
        }
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()
                || node.longValue() < min || node.longValue() > max) {
            throw new BizException(4001, "整数参数非法: " + name);
        }
        return node.longValue();
    }
}
