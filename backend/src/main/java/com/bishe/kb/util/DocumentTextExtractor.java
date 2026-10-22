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

/**
 * 文档正文抽取：把各种格式统一转成纯文本，供后续分块与向量化。
 *
 * <p>PDF 走 PDFBox（版面顺序更稳定、可控），其余格式（doc/docx/ppt/xls/md/txt/html/csv…）走 Tika。
 * 扫描件需要 OCR，二期接 tika-parser-pdf-module + tesseract。
 */
@Slf4j
public final class DocumentTextExtractor {

    private static final Tika TIKA = new Tika();

    static {
        // Tika 内部解析器对超大文件会截断，这里放开到 64MB（与上传限制保持一致）
        TIKA.setMaxStringLength(64 * 1024 * 1024);
    }

    private DocumentTextExtractor() {
    }

    /**
     * @param file      已落盘的本地文件
     * @param fileType  后缀（pdf / docx / md …），决定解析策略
     * @return 抽取出的纯文本；解析不出内容时返回空串而不是抛异常
     */
    public static String extract(Path file, String fileType) {
        try {
            if ("pdf".equalsIgnoreCase(fileType)) {
                return extractPdf(file);
            }
            return extractByTika(file);
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
     * <p>用于给分段打页码标签——每页单独 {@code setStartPage/setEndPage} 抽取，
     * 从而知道某个片段来自哪一页，前端点击引用即可 {@code #page=N} 跳到命中页。
     * 非 PDF 或解析失败时返回空列表，由调用方退化为「无页码」处理。
     */
    public static List<String> extractPdfPages(Path file) {
        List<String> pages = new ArrayList<>();
        try (var document = Loader.loadPDF(file.toFile())) {
            int pageCount = document.getNumberOfPages();
            for (int page = 1; page <= pageCount; page++) {
                PDFTextStripper stripper = newStripper();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pages.add(stripper.getText(document));
            }
        } catch (Exception e) {
            log.error("PDF 逐页抽取失败: {}", file, e);
            return List.of();
        }
        return pages;
    }

    /** 统一配置 PDFBox 抽取器：按坐标排序减少双栏/页眉错乱，段落间补换行 */
    private static PDFTextStripper newStripper() {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setLineSeparator("\n");
        stripper.setParagraphStart("");
        stripper.setParagraphEnd("\n");
        return stripper;
    }

    private static String extractByTika(Path file) throws IOException, TikaException {
        String raw = TIKA.parseToString(file.toFile());
        return raw == null ? "" : raw;
    }
}
