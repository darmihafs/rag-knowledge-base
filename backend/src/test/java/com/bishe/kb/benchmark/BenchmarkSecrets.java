package com.bishe.kb.benchmark;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * 跑批用的密钥解析：只从环境变量或本地私密配置读，绝不把密钥写进测试代码或报告。
 *
 * <p>{@code application-local.yml} 已被 .gitignore 忽略，仓库里只有占位说明；
 * 评测需要真实向量模型，所以这里显式去这份文件里取 {@code rag.api-key}，
 * 取不到时让跑批以「跳过」结束而不是报一堆连接错误。
 */
final class BenchmarkSecrets {

    /** 相对 backend/ 工作目录的两种可能位置（Maven 在模块目录 fork 测试进程） */
    private static final List<Path> LOCAL_CONFIGS = List.of(
            Path.of("src/main/resources/application-local.yml"),
            Path.of("backend/src/main/resources/application-local.yml"));

    /** 生产配置文件：跑批要引用其中的切分粒度与召回名额，而不是测试 profile 里改小的值 */
    private static final List<Path> MAIN_CONFIGS = List.of(
            Path.of("src/main/resources/application.yml"),
            Path.of("backend/src/main/resources/application.yml"));

    private BenchmarkSecrets() {
    }

    /**
     * 读取 {@code application.yml} 里的生产默认值（rag.chunk-max-chars / chunk-overlap-chars / top-k）。
     *
     * <p>报告必须引用真实生效的默认参数：测试 profile 为了便于验证把 chunk-max-chars 改成了 300、
     * top-k 改成 3，直接拿 {@code RagProperties} 的值写进论文会是错的。文件读不到时返回空 Properties，
     * 由调用方退回运行时配置。
     */
    static Properties productionRagConfig() {
        for (Path path : MAIN_CONFIGS) {
            if (!Files.isRegularFile(path)) {
                continue;
            }
            try {
                YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
                factory.setResources(new FileSystemResource(path));
                Properties properties = factory.getObject();
                if (properties != null) {
                    return properties;
                }
            } catch (RuntimeException e) {
                System.out.println("[benchmark] 读取生产配置 " + path + " 失败: " + e.getMessage());
            }
        }
        return new Properties();
    }

    /** 解析真实 embedding 密钥；不可用时返回空串，由调用方决定跳过还是报错 */
    static String resolveApiKey() {
        String fromEnv = System.getenv("RAG_API_KEY");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        for (Path path : LOCAL_CONFIGS) {
            if (!Files.isRegularFile(path)) {
                continue;
            }
            try {
                YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
                factory.setResources(new FileSystemResource(path));
                Properties properties = factory.getObject();
                String value = properties == null ? null : properties.getProperty("rag.api-key");
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            } catch (RuntimeException e) {
                System.out.println("[benchmark] 读取 " + path + " 失败: " + e.getMessage());
            }
        }
        return "";
    }
}
