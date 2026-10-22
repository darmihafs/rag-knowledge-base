package com.bishe.kb;

import com.bishe.kb.config.AsyncConfig;
import com.bishe.kb.service.KbDocumentService;
import com.bishe.kb.service.impl.VectorIndexService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 异步入库编排测试：用 Mock 线程池“扣住”后台任务，逐帧验证状态机与失败重试。
 *
 * <p>真实线程池跑得太快，上传返回时往往已经 INDEXED，无法稳定断言「上传先返回、解析在后台」。
 * 这里把 executor 换成只记录 Runnable 的 Mock，由测试手动决定任务何时开始，
 * 覆盖：PENDING 停留、队列满降级、解析中拒绝重入、FAILED 重试、启动恢复。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentAsyncIngestIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KbDocumentService documentService;

    @MockitoBean(name = AsyncConfig.DOC_INGEST_EXECUTOR)
    private ThreadPoolTaskExecutor documentIngestExecutor;

    @MockitoBean
    private VectorIndexService vectorIndexService;

    @Value("${rag.upload-dir}")
    private String uploadDir;

    private final List<Runnable> submitted = new ArrayList<>();
    private final List<Long> createdIds = new ArrayList<>();

    @BeforeEach
    void captureSubmittedTasks() {
        submitted.clear();
        doAnswer(invocation -> {
            submitted.add(invocation.getArgument(0));
            return null;
        }).when(documentIngestExecutor).execute(any(Runnable.class));
    }

    @AfterEach
    void cleanUp() {
        for (Long id : createdIds) {
            try {
                if (documentService.getById(id) != null) {
                    documentService.removeDocument(id);
                }
            } catch (RuntimeException ignored) {
                // 清理失败不影响断言结果，测试库随上下文销毁
            }
        }
        createdIds.clear();
        submitted.clear();
    }

    @Test
    @DisplayName("上传只落盘入队：工作线程未执行时保持 PENDING、零切片，执行后才转 INDEXED")
    void uploadReturnsBeforeParsingAndCompletesInBackground() throws Exception {
        JsonNode body = upload("异步待解析.txt", buildText(900));
        assertThat(body.get("code").asInt()).isZero();
        long id = body.get("data").asLong();
        createdIds.add(id);

        assertThat(documentStatus(id)).isEqualTo("PENDING");
        assertThat(chunkRows(id)).isZero();
        assertThat(submitted).as("上传返回时任务应已入队但尚未执行").hasSize(1);

        submitted.get(0).run();

        assertThat(documentStatus(id)).isEqualTo("INDEXED");
        assertThat(chunkRows(id)).isPositive();
        assertThat(jdbcTemplate.queryForObject(
                "select chunk_count from kb_document where id = ?", Integer.class, id)).isEqualTo(chunkRows(id));
    }

    @Test
    @DisplayName("解析队列已满：上传返回 5005，文档落为 FAILED 且没有半截切片")
    void queueFullMarksDocumentFailed() throws Exception {
        doThrow(new TaskRejectedException("queue full"))
                .when(documentIngestExecutor).execute(any(Runnable.class));

        JsonNode body = upload("队列已满.txt", buildText(600));

        assertThat(body.get("code").asInt()).isEqualTo(5005);
        assertThat(body.get("message").asText()).contains("队列已满");
        long id = jdbcTemplate.queryForObject(
                "select id from kb_document where title = '队列已满'", Long.class);
        createdIds.add(id);
        assertThat(documentStatus(id)).isEqualTo("FAILED");
        assertThat(errorMsg(id)).contains("队列已满");
        assertThat(chunkRows(id)).isZero();
    }

    @Test
    @DisplayName("重新解析：PENDING 时返回 4090 拒绝重入；FAILED 时清空旧切片并重新走完解析")
    void reindexRejectsBusyDocumentAndRebuildsFailedOne() throws Exception {
        long id = upload("重试解析.txt", buildText(900)).get("data").asLong();
        createdIds.add(id);

        JsonNode busy = postJson("/api/documents/" + id + "/reindex");
        assertThat(busy.get("code").asInt()).isEqualTo(4090);
        assertThat(documentStatus(id)).isEqualTo("PENDING");

        // 让首次任务完成，再把它还原成「有失败原因、旧切片已清理」的现场
        submitted.get(0).run();
        assertThat(documentStatus(id)).isEqualTo("INDEXED");
        jdbcTemplate.update("update kb_document set status = 'FAILED', error_msg = '模拟失败' where id = ?", id);
        jdbcTemplate.update("delete from kb_chunk where document_id = ?", id);

        submitted.clear();
        JsonNode retried = postJson("/api/documents/" + id + "/reindex");
        assertThat(retried.get("code").asInt()).isZero();
        assertThat(documentStatus(id)).isEqualTo("PENDING");
        assertThat(submitted).hasSize(1);

        submitted.get(0).run();

        assertThat(documentStatus(id)).isEqualTo("INDEXED");
        assertThat(errorMsg(id)).isNull();
        assertThat(chunkRows(id)).isPositive();
    }

    @Test
    @DisplayName("启动恢复：PENDING/PARSING 重新入队并完成，原文缺失的标记 FAILED")
    void startupRecoveryRequeuesInterruptedDocuments() throws Exception {
        String pendingPath = writeStoredFile("启动恢复：待处理文档的正文内容。".repeat(30));
        String parsingPath = writeStoredFile("启动恢复：解析中断文档的正文内容。".repeat(30));
        long pendingId = insertDocument("重启待处理.txt", pendingPath, "PENDING");
        long parsingId = insertDocument("重启解析中.txt", parsingPath, "PARSING");
        long missingId = insertDocument("重启原文缺失.txt", "async-test/not-exists.txt", "PENDING");
        createdIds.addAll(List.of(pendingId, parsingId, missingId));

        int requeued = documentService.requeueInterrupted();

        assertThat(requeued).isEqualTo(2);
        assertThat(submitted).hasSize(2);
        submitted.forEach(Runnable::run);

        assertThat(documentStatus(pendingId)).isEqualTo("INDEXED");
        assertThat(documentStatus(parsingId)).isEqualTo("INDEXED");
        assertThat(chunkRows(pendingId)).isPositive();
        assertThat(documentStatus(missingId)).isEqualTo("FAILED");
        assertThat(errorMsg(missingId)).contains("原文文件缺失");
    }

    @Test
    @DisplayName("向量化阶段异常：已提交的切片不回滚，文档保持 INDEXED 并可按词法检索")
    void vectorFailureAfterPublishKeepsCommittedChunks() throws Exception {
        doThrow(new RuntimeException("模拟向量服务异常")).when(vectorIndexService).indexChunks(anyList());

        long id = upload("向量失败但已入库.txt", buildText(600)).get("data").asLong();
        createdIds.add(id);
        submitted.get(0).run();

        assertThat(documentStatus(id)).isEqualTo("INDEXED");
        assertThat(chunkRows(id)).isPositive();
        assertThat(errorMsg(id)).isNull();
    }

    // ------------------------------------------------------------------ 辅助

    private JsonNode upload(String fileName, String content) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", fileName, "text/plain; charset=UTF-8", content.getBytes(StandardCharsets.UTF_8));
        MvcResult result = mockMvc.perform(multipart("/api/documents/upload").file(file))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode postJson(String url) throws Exception {
        MvcResult result = mockMvc.perform(post(url)).andExpect(status().isOk()).andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String documentStatus(long id) {
        return jdbcTemplate.queryForObject("select status from kb_document where id = ?", String.class, id);
    }

    private String errorMsg(long id) {
        return jdbcTemplate.queryForObject("select error_msg from kb_document where id = ?", String.class, id);
    }

    private int chunkRows(long documentId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from kb_chunk where document_id = ?", Integer.class, documentId);
    }

    private String writeStoredFile(String content) throws Exception {
        Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
        Path relative = Paths.get("async-test")
                .resolve(UUID.randomUUID().toString().replace("-", "") + ".txt");
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return relative.toString().replace('\\', '/');
    }

    private long insertDocument(String title, String relativePath, String statusValue) throws Exception {
        Path stored = Paths.get(uploadDir).toAbsolutePath().normalize().resolve(relativePath);
        long size = Files.exists(stored) ? Files.size(stored) : 0L;
        jdbcTemplate.update("insert into kb_document "
                        + "(space_id, title, file_type, file_path, file_size, status, chunk_count, deleted) "
                        + "values (1, ?, 'txt', ?, ?, ?, 0, 0)",
                title, relativePath, size, statusValue);
        return jdbcTemplate.queryForObject("select id from kb_document where title = ?", Long.class, title);
    }

    private String buildText(int length) {
        StringBuilder sb = new StringBuilder();
        int index = 1;
        while (sb.length() < length) {
            sb.append("第").append(index).append("段：异步入库把文件落盘与解析解耦，")
                    .append("请求线程只负责保存原文件和入队，后台线程再完成抽取、分块与向量化。\n\n");
            index++;
        }
        return sb.toString();
    }
}
