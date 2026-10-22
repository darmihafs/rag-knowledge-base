package com.bishe.kb;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 私有知识库问答系统（RAG）后端启动类。
 *
 * <p>分层约定：controller 只做参数校验与响应封装，业务编排在 service，
 * 数据访问走 mapper，领域对象集中在 model，第三方能力（LLM / 向量库 / 解析器）在 config 与 util。
 */
@SpringBootApplication
@MapperScan("com.bishe.kb.mapper")
public class KnowledgeBaseApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowledgeBaseApplication.class, args);
    }
}
