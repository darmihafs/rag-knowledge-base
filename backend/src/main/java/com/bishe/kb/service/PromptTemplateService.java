package com.bishe.kb.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.bishe.kb.model.dto.TemplateSaveRequest;
import com.bishe.kb.model.entity.PromptTemplate;
import com.bishe.kb.model.vo.TemplateVO;

import java.util.List;

/**
 * 提示词模板市场：把「怎么问」沉淀成可复用的快捷指令。
 *
 * <p>模板正文里的 {@code {topic}} 是写给用户看的占位提醒，后端不做替换——
 * 点击胶囊由前端把整段前缀填进输入框，用户补上主题词再发送。
 */
public interface PromptTemplateService extends IService<PromptTemplate> {

    /** 聊天页用：只返回启用中的模板，按 sort_order 升序 */
    List<TemplateVO> listActive();

    /** 管理端用：全量模板（含已下架），按 sort_order 升序 */
    List<TemplateVO> listAll();

    /** 新建模板，名称重复或分类非法抛业务异常 */
    TemplateVO create(TemplateSaveRequest request);

    /** 修改模板（含启用/下架），id 不存在抛 4040 */
    TemplateVO update(Long id, TemplateSaveRequest request);

    /** 删除模板 */
    void delete(Long id);
}
