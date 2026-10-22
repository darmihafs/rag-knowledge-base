package com.bishe.kb.service;

import com.bishe.kb.model.dto.SystemConfigRequest;
import com.bishe.kb.model.vo.SystemConfigVO;

/**
 * RAG 引擎参数的运行时读写。
 *
 * <p>直接改的是容器里那个 {@code RagProperties} 单例，因此保存后下一次问答就用新值，
 * 不需要重启；代价是进程重启后回到 {@code application.yml} 的出厂配置——
 * 这是有意为之：把管理页的临时调参和仓库里的声明式配置分开，避免线上改的东西无处追溯。
 */
public interface SystemConfigService {

    SystemConfigVO current();

    /** 部分更新：入参里为 null 的字段保持原值 */
    SystemConfigVO update(SystemConfigRequest request);

    /** 把可改参数恢复成进程启动时从 yaml 读到的出厂快照 */
    SystemConfigVO resetDefaults();
}
