package com.bishe.kb.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分块工具单测：保证块大小上限与重叠逻辑不被后续改动破坏。
 */
class TextChunkerTest {

    @Test
    void 按段落聚合_不超过上限() {
        String text = "第一段内容。\n\n第二段内容。\n\n第三段内容。";
        List<String> chunks = TextChunker.chunk(text, 100, 20);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).contains("第一段内容").contains("第三段内容");
    }

    @Test
    void 超长段落_滑动窗口切分且有重叠() {
        String longParagraph = "A".repeat(250);
        List<String> chunks = TextChunker.chunk(longParagraph, 100, 20);
        assertThat(chunks).hasSizeGreaterThanOrEqualTo(3);
        assertThat(chunks).allMatch(c -> c.length() <= 100);
        // 步长 = 100 - 20 = 80，因此第二块开头应能回溯到第一块尾部
        assertThat(chunks.get(1).startsWith("A")).isTrue();
    }

    @Test
    void 空文本_返回空列表() {
        assertThat(TextChunker.chunk("   \n\n  ", 500, 100)).isEmpty();
    }

    @Test
    void 带偏移切分_偏移能原样截回正文() {
        // 页码映射依赖「正文 == 原文子串」：偏移一旦和 trim/拼接口径不一致，引用就会跳到错误页
        String text = "第一段内容。\n\n" + "B".repeat(150) + "\n\n最后一段内容。";
        List<TextChunker.ChunkSlice> slices = TextChunker.chunkWithOffsets(text, 100, 20);

        assertThat(slices).isNotEmpty();
        for (TextChunker.ChunkSlice slice : slices) {
            assertThat(text.substring(slice.startOffset(), slice.endOffset())).isEqualTo(slice.content());
        }
    }
}
