package com.bishe.kb.util;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分块：RAG 召回质量的第一道闸门。
 *
 * <p>策略：优先按空行切段落并聚合成不超过 maxChars 的块；段落本身超长时退化为
 * 滑动窗口（maxChars - overlap 为步长），保证语义边界的同时控制 token 数。
 *
 * <p>同时保留每个块在<b>规范化全文</b>中的起止偏移，供 PDF 分块把片段映射回页码。
 * 页码映射必须和分块使用同一份规范化文本，否则空白压缩会让偏移整体漂移。
 */
public final class TextChunker {

    private TextChunker() {
    }

    /** 一个切片的正文与它在规范化全文中的位置（左闭右开） */
    public record ChunkSlice(String content, int startOffset, int endOffset) {
    }

    public static List<String> chunk(String text, int maxChars, int overlap) {
        return chunkWithOffsets(text, maxChars, overlap).stream()
                .map(ChunkSlice::content)
                .toList();
    }

    /**
     * 带偏移量的分块：正文规则与 {@link #chunk} 完全一致，只是额外记录每块在
     * {@link #normalize(String)} 结果中的起始/结束位置。
     *
     * @param text     文档纯文本
     * @param maxChars 单块最大字符数
     * @param overlap  相邻块重叠字符数（窗口模式下生效），应小于 maxChars
     */
    public static List<ChunkSlice> chunkWithOffsets(String text, int maxChars, int overlap) {
        List<ChunkSlice> chunks = new ArrayList<>();
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return chunks;
        }
        int size = Math.max(100, maxChars);
        int stride = Math.max(50, size - Math.max(0, overlap));

        StringBuilder buffer = new StringBuilder();
        int bufferStart = -1;
        int cursor = 0;
        while (cursor <= normalized.length()) {
            int separator = normalized.indexOf("\n\n", cursor);
            int paragraphEnd = separator < 0 ? normalized.length() : separator;
            String raw = normalized.substring(cursor, paragraphEnd);
            String paragraph = StringUtils.trimToEmpty(raw);
            if (!paragraph.isEmpty()) {
                // trim 只去掉首尾空白，起始偏移 = 段首 + 前导空白长度
                int paragraphStart = cursor + raw.indexOf(paragraph);
                if (paragraph.length() > size) {
                    // 超长段落：先冲掉已有 buffer，再走滑动窗口
                    flush(chunks, buffer, bufferStart);
                    bufferStart = -1;
                    chunks.addAll(slidingWindow(normalized, paragraphStart,
                            paragraphStart + paragraph.length(), size, stride));
                } else {
                    if (buffer.length() + paragraph.length() + 2 > size) {
                        flush(chunks, buffer, bufferStart);
                        bufferStart = -1;
                    }
                    if (buffer.length() == 0) {
                        bufferStart = paragraphStart;
                    } else {
                        buffer.append("\n\n");
                    }
                    buffer.append(paragraph);
                }
            }
            if (separator < 0) {
                break;
            }
            cursor = separator + 2;
        }
        flush(chunks, buffer, bufferStart);
        return chunks;
    }

    /** 统一换行、压缩连续空行并去掉首尾空白；分块与页码偏移都以它为准 */
    public static String normalize(String text) {
        return StringUtils.trimToEmpty(text)
                .replaceAll("\r\n?", "\n")
                .replaceAll("\n{3,}", "\n\n");
    }

    private static List<ChunkSlice> slidingWindow(String text, int start, int end, int size, int stride) {
        List<ChunkSlice> result = new ArrayList<>();
        for (int from = start; from < end; from += stride) {
            int to = Math.min(end, from + size);
            String piece = text.substring(from, to);
            String content = StringUtils.trimToEmpty(piece);
            if (!content.isEmpty()) {
                int actualStart = from + piece.indexOf(content);
                result.add(new ChunkSlice(content, actualStart, actualStart + content.length()));
            }
            if (to == end) {
                break;
            }
        }
        return result;
    }

    private static void flush(List<ChunkSlice> chunks, StringBuilder buffer, int bufferStart) {
        String value = StringUtils.trimToEmpty(buffer.toString());
        if (!value.isEmpty() && bufferStart >= 0) {
            chunks.add(new ChunkSlice(value, bufferStart, bufferStart + value.length()));
        }
        buffer.setLength(0);
    }
}
