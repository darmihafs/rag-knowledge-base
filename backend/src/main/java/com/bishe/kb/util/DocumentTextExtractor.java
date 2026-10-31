package com.bishe.kb.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文档正文抽取：把各种格式统一转成纯文本，供后续分块与向量化。
 *
 * <p>PDF 走 PDFBox（版面顺序更稳定、可控），其余格式（doc/docx/ppt/xls/md/txt/html/csv…）走 Tika。
 * 扫描件需要 OCR，二期接 tika-parser-pdf-module + tesseract。
 *
 * <p>正文上限 {@value #MAX_TEXT_CHARS} 字符（约 16MB UTF-8）：超过直接让文档失败并给出明确原因，
 * 而不是悄悄截断后入库一份残缺语料，也避免一份超大文件把堆内存打满。
 */
@Slf4j
public final class DocumentTextExtractor {

    /** 单篇文档允许进入分块链路的正文上限（字符数） */
    public static final int MAX_TEXT_CHARS = 8 * 1024 * 1024;

    /** PDF 单次遍历时页与页之间的分隔标记；正文里几乎不可能出现，切页后不再保留 */
    private static final String PAGE_SEPARATOR = "\u0001KBPAGE\u0001";

    private static final Pattern PAGE_SEPARATOR_PATTERN = Pattern.compile(Pattern.quote(PAGE_SEPARATOR));

    private static final Tika TIKA = new Tika();

    static {
        // 与正文上限保持一致；Tika 超过上限会截断，抽取后还会再核对一次长度
        TIKA.setMaxStringLength(MAX_TEXT_CHARS);
    }

    private DocumentTextExtractor() {
    }

    /**
     * @param file     已落盘的本地文件
     * @param fileType 后缀（pdf / docx / md …），决定解析策略
     * @return 抽取出的纯文本；解析不出内容时返回空串而不是抛异常
     * @throws TextTooLargeException 正文超过 {@link #MAX_TEXT_CHARS}，调用方应把文档标记为失败
     */
    public static String extract(Path file, String fileType) {
        try {
            String text = "pdf".equalsIgnoreCase(fileType) ? extractPdf(file) : extractByTika(file);
            return ensureWithinLimit(text, file);
        } catch (TextTooLargeException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析文档失败: {} ({})", file, fileType, e);
            return "";
        }
    }

    /** 流式解析（不落盘，用于上传接口预览） */
    public static String extract(InputStream in, String name) throws IOException, TikaException {
        return TIKA.parseToString(in);
    }

    private static String extractPdf(Path file) throws IOException {
        try (var document = Loader.loadPDF(file.toFile())) {
            return newStripper().getText(document);
        }
    }

    /**
     * PDF 逐页抽取：返回每一页的纯文本，列表下标 0 即第 1 页。
     *
     * <p><b>单次遍历</b>完成：只把文档树走一遍，用页分隔标记切页；不再对每一页
     * {@code setStartPage/setEndPage + getText}，避免大 PDF 被重复解析 N 次（CPU 与堆内存都随页数放大）。
     *
     * <p>用于给分段打页码标签，前端点击引用即可 {@code #page=N} 跳到命中页。
     * 非 PDF 或解析失败时返回空列表，由调用方退化为「无页码」处理。
     */
    public static List<String> extractPdfPages(Path file) {
        try (var document = Loader.loadPDF(file.toFile())) {
            String text = newPagedStripper().getText(document);
            ensureWithinLimit(text, file);
            return splitPages(text);
        } catch (TextTooLargeException e) {
            throw e;
        } catch (Exception e) {
            log.error("PDF 逐页抽取失败: {}", file, e);
            return List.of();
        }
    }

    private static List<String> splitPages(String text) {
        String[] parts = PAGE_SEPARATOR_PATTERN.split(text, -1);
        List<String> pages = new ArrayList<>(parts.length);
        for (String part : parts) {
            pages.add(part);
        }
        return pages;
    }

    /** 统一配置 PDFBox 抽取器：按坐标排序减少双栏/页眉错乱，段落间补换行 */
    private static PDFTextStripper newStripper() {
        return configure(new PDFTextStripper());
    }

    /**
     * 逐页抽取器：在每页开始处写入分隔标记，文档树仍然只遍历一次。
     *
     * <p>PDFBox 没有现成的「页分隔符」配置，但 {@code processPage} 是逐页回调的公开钩子，
     * 计数后直接往输出流写标记即可；相比 {@code setStartPage/setEndPage + getText} 的循环，
     * 大文档不会被重复解析 N 次。
     */
    private static PDFTextStripper newPagedStripper() {
        return configure(new PDFTextStripper() {
            private int processedPages;

            @Override
            public void processPage(org.apache.pdfbox.pdmodel.PDPage page) throws IOException {
                if (processedPages++ > 0) {
                    output.write(PAGE_SEPARATOR);
                }
                super.processPage(page);
            }
        });
    }

    private static PDFTextStripper configure(PDFTextStripper stripper) {
        stripper.setSortByPosition(true);
        stripper.setLineSeparator("\n");
        stripper.setParagraphStart("");
        stripper.setParagraphEnd("\n");
        return stripper;
    }

    private static String ensureWithinLimit(String text, Path file) {
        String value = text == null ? "" : text;
        if (value.length() >= MAX_TEXT_CHARS) {
            throw new TextTooLargeException("文档正文超过 " + MAX_TEXT_CHARS
                    + " 字符上限，请拆分后再上传: " + file.getFileName());
        }
        return value;
    }

    private static String extractByTika(Path file) throws IOException, TikaException {
        String raw = TIKA.parseToString(file.toFile());
        return raw == null ? "" : raw;
    }

    /** 正文超限：单独类型是为了让上层能把它当成「可读的失败原因」，而不是普通解析异常 */
    public static class TextTooLargeException extends RuntimeException {
        public TextTooLargeException(String message) {
            super(message);
        }
    }
}
