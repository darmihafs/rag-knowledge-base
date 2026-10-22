package com.bishe.kb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bishe.kb.model.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
