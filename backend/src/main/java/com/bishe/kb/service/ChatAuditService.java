package com.bishe.kb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bishe.kb.model.dto.AuditPageQuery;
import com.bishe.kb.model.vo.AuditDetailVO;
import com.bishe.kb.model.vo.AuditMessageVO;

/**
 * 全站问答审计：管理员视角检索所有人的问答记录。
 *
 * <p>普通用户的会话接口按 {@code user_id} 隔离，本服务是唯一的跨用户读取入口，
 * 因此对应的 HTTP 接口必须锁在管理员权限下。
 */
public interface ChatAuditService {

    Page<AuditMessageVO> pageMessages(AuditPageQuery query);

    /** 按提问消息 ID 取完整问答与引用；消息不存在或不是提问消息时抛业务异常 */
    AuditDetailVO detail(Long messageId);
}
