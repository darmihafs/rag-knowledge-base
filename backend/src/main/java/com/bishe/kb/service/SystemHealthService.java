package com.bishe.kb.service;

import com.bishe.kb.model.vo.DashboardHealthVO;

/**
 * 系统核心组件体检：数据库连接池、大模型、向量引擎、本地向量库文件。
 *
 * <p>口径必须诚实：这里只区分「在线 / 降级 / 离线 / 未启用」四种状态，
 * 对没真正探活过的组件（比如不发起远程调用的大模型）明确写成「配置就绪」而不是「已连通」。
 */
public interface SystemHealthService {

    DashboardHealthVO health();
}
