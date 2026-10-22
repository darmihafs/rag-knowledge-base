package com.bishe.kb.controller;

import com.bishe.kb.common.Result;
import com.bishe.kb.model.dto.SessionCreateRequest;
import com.bishe.kb.model.vo.ChatMessageVO;
import com.bishe.kb.model.vo.SessionVO;
import com.bishe.kb.service.ChatSessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话管理接口：左侧历史列表的增删查，以及切换会话时拉取历史消息。
 *
 * <ul>
 *   <li>{@code GET    /api/sessions}               —— 会话列表，按最后活跃时间倒序</li>
 *   <li>{@code POST   /api/sessions}               —— 新建会话，标题默认「新对话」</li>
 *   <li>{@code DELETE /api/sessions/{id}}           —— 删除会话并清理名下消息</li>
 *   <li>{@code GET    /api/sessions/{id}/messages}  —— 该会话的全部历史消息（时间正序）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final ChatSessionService sessionService;

    @GetMapping
    public Result<List<SessionVO>> list() {
        return Result.ok(sessionService.listSessions());
    }

    /** body 可以整个不传，也可以传 {"title":"自定义标题"} */
    @PostMapping
    public Result<SessionVO> create(@Valid @RequestBody(required = false) SessionCreateRequest request) {
        return Result.ok(sessionService.createSession(request == null ? null : request.title()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        sessionService.deleteSession(id);
        return Result.ok();
    }

    @GetMapping("/{id}/messages")
    public Result<List<ChatMessageVO>> messages(@PathVariable Long id) {
        return Result.ok(sessionService.listMessages(id));
    }
}
