package com.bishe.kb.util;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分块：RAG 召回质量的第一道闸门。
 *
 * <p>策略：优先按空行切段落并聚合成不超过 maxChars 的块；段落本身超长时退化为
 * 滑动窗口（maxChars - overlap 为步长），保证语义边界的同时控制 token 数。
 */
public final class TextChunker {

    private TextChunker() {
    }

    /**
     * @param text     文档纯文本
     * @param maxChars 单块最大字符数
     * @param overlap  相邻块重叠字符数（窗口模式下生效），应小于 maxChars
     */
    public static List<String> chunk(String text, int maxChars, int overlap) {
        List<String> chunks = new ArrayList<>();
        String normalized = StringUtils.trimToEmpty(text)
                // 统一换行 + 压缩连续空行
                .replaceAll("\r\n?", "\n")
                .replaceAll("\n{3,}", "\n\n");
        if (normalized.isEmpty()) {
            return chunks;
        }
        int size = Math.max(100, maxChars);
        int stride = Math.max(50, size - Math.max(0, overlap));

        StringBuilder buffer = new StringBuilder();
        for (String paragraph : normalized.split("\n\n")) {
            String p = StringUtils.trimToEmpty(paragraph);
            if (p.isEmpty()) {
                continue;
            }
            if (p.length() > size) {
                // 超长段落：先冲掉已有 buffer，再走滑动窗口
                flush(buffer, chunks);
                chunks.addAll(slidingWindow(p, size, stride));
                continue;
            }
            if (buffer.length() + p.length() + 2 > size) {
                flush(buffer, chunks);
            }
            if (buffer.length() > 0) {
                buffer.append("\n\n");
            }
            buffer.append(p);
        }
        flush(buffer, chunks);
        return chunks;
    }

    private static List<String> slidingWindow(String text, int size, int stride) {
        List<String> result = new ArrayList<>();
        for (int start = 0; start < text.length(); start += stride) {
            int end = Math.min(text.length(), start + size);
            result.add(text.substring(start, end));
            if (end == text.length()) {
                break;
            }
        }
        return result;
    }

    private static void flush(StringBuilder buffer, List<String> chunks) {
        String value = StringUtils.trimToEmpty(buffer.toString());
        if (!value.isEmpty()) {
            chunks.add(value);
        }
        buffer.setLength(0);
    }
}
