package com.bishe.kb.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bishe.kb.common.Result;
import com.bishe.kb.model.dto.SpaceMemberRequest;
import com.bishe.kb.model.dto.SpacePageQuery;
import com.bishe.kb.model.dto.SpaceSaveRequest;
import com.bishe.kb.model.vo.SpaceMemberVO;
import com.bishe.kb.model.vo.SpaceVO;
import com.bishe.kb.service.KbSpaceService;
import com.bishe.kb.service.SpaceAccessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库空间管理接口。
 *
 * <ul>
 *   <li>{@code GET    /api/spaces}               —— 分页查询空间（keyword 可选）</li>
 *   <li>{@code GET    /api/spaces/all}           —— 全部空间，供前端侧栏一次性加载</li>
 *   <li>{@code GET    /api/spaces/visible}       —— 当前登录用户可见的空间，普通用户唯一入口</li>
 *   <li>{@code POST   /api/spaces}               —— 新建空间</li>
 *   <li>{@code PUT    /api/spaces/{id}}          —— 编辑空间名称 / 描述</li>
 *   <li>{@code DELETE /api/spaces/{id}}          —— 删除空间（默认空间或仍有文档时拒绝）</li>
 *   <li>{@code GET    /api/spaces/{id}/members}  —— 查询空间成员</li>
 *   <li>{@code POST   /api/spaces/{id}/members}  —— 批量授权成员（幂等，已存在的跳过）</li>
 *   <li>{@code DELETE /api/spaces/{id}/members/{userId}} —— 回收单个成员（创建人不可回收）</li>
 * </ul>
 *
 * <p>除 {@code /visible} 外都在 {@code /api/spaces} 前缀下，由 AuthInterceptor 收为「仅管理员」；
 * {@code /visible} 单独放行给登录用户，因为普通用户要靠它决定问答时能选哪个空间。
 * 成员管理是纯管理员动作——授权本身就是一种权限，不能让用户自行加入别人的私有空间。
 */
@RestController
@RequestMapping("/api/spaces")
@RequiredArgsConstructor
public class SpaceController {

    private final KbSpaceService spaceService;
    private final SpaceAccessService spaceAccessService;

    @GetMapping
    public Result<Page<SpaceVO>> page(@Valid SpacePageQuery query) {
        return Result.ok(spaceService.pageSpaces(query));
    }

    @GetMapping("/all")
    public Result<List<SpaceVO>> all() {
        return Result.ok(spaceService.listSpaces());
    }

    /**
     * 当前登录用户可见的空间列表：管理员拿到全部，普通用户只拿到自己有成员关系的。
     *
     * <p>与普通用户问答时的召回范围是同一份口径（都由 {@link SpaceAccessService} 决定），
     * 前端照它渲染下拉框，不会出现「能选到却检索不到」的空间。
     */
    @GetMapping("/visible")
    public Result<List<SpaceVO>> visible() {
        return Result.ok(spaceAccessService.visibleSpaces());
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody SpaceSaveRequest request) {
        return Result.ok(spaceService.createSpace(request));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody SpaceSaveRequest request) {
        spaceService.updateSpace(id, request);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        spaceService.deleteSpace(id);
        return Result.ok();
    }

    @GetMapping("/{id}/members")
    public Result<List<SpaceMemberVO>> members(@PathVariable Long id) {
        return Result.ok(spaceAccessService.members(id));
    }

    /** 批量授权：已存在的成员会被跳过，重复调用不会插出重复行 */
    @PostMapping("/{id}/members")
    public Result<Integer> grantMembers(@PathVariable Long id, @Valid @RequestBody SpaceMemberRequest request) {
        return Result.ok(spaceAccessService.grant(id, request.userIds()));
    }

    @DeleteMapping("/{id}/members/{userId}")
    public Result<Integer> revokeMember(@PathVariable Long id, @PathVariable Long userId) {
        return Result.ok(spaceAccessService.revoke(id, List.of(userId)));
    }
}
