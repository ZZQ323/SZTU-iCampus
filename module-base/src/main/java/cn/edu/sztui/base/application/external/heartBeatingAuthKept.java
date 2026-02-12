package cn.edu.sztui.base.application.external;

import cn.edu.sztui.base.application.service.AuthService;
import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.base.infrastructure.util.cache.dto.ProxySession;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.Map;

@Slf4j
@Component
public class heartBeatingAuthKept {

    @Resource
    private AuthService authService;

    @Resource
    private AuthSessionCacheUtil authSessionCacheUtil;

    @Scheduled(fixedRate = 50000)  // 半分钟执行一次
    public void authKeeping()
    {
        Map<String, ProxySession> sessions = authSessionCacheUtil.getAllSessions();
        // boolean ret = authService.getSessionStatus();

    }
}
