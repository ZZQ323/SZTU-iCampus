package cn.edu.sztui.stream.web;

import cn.edu.sztui.common.util.result.Result;
import cn.edu.sztui.stream.infrastructure.websocket.registry.WsSessionRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * WebSocket 连接状态接口
 * <p>
 * 替代原 SseController：
 * - 删除了所有 SSE 订阅接口（/sse/v1/schedule 等）→ 已被 WebSocket /ws 端点替代
 * - 保留连接状态查询和统计接口，改用 WsSessionRegistry
 * <p>
 * 文件位置：module-stream/.../web/WsStatusController.java
 */
@Slf4j
@RestController
@RequestMapping("/ws-status")
@Tag(name = "WebSocket状态接口", description = "WebSocket 连接状态查询")
public class WsStatusController {

    @Resource
    private WsSessionRegistry wsSessionRegistry;

    /**
     * 获取系统连接统计
     */
    @GetMapping("/v1/stats")
    @Operation(summary = "获取系统连接统计")
    public Result getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("schedule", wsSessionRegistry.getConnectionCount("schedule"));
        stats.put("announcement", wsSessionRegistry.getConnectionCount("announcement"));
        stats.put("calendar", wsSessionRegistry.getConnectionCount("calendar"));
        stats.put("total", wsSessionRegistry.getTotalConnectionCount());
        return Result.ok(stats);
    }
}