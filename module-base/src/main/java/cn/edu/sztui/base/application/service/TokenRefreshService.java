package cn.edu.sztui.base.application.service;

import cn.edu.sztui.base.application.vo.TokenAuthVo;
import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.common.cache.dto.TokenMeta;
import cn.edu.sztui.common.util.jwt.JwtConfig;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Token 生命周期管理服务（独立 Service，其他模块可注入调用）
 * <p>
 * 职责：
 * 1. 初始化 token（创建 JWT + Redis TokenMeta）
 * 2. 刷新过期 token（滑动窗口内签发新 JWT，继承状态）
 * 3. 更新 lastAccessTime（活跃续期）
 * 4. 获取 sessionKey（从 Redis）
 */
@Slf4j
@Service
public class TokenRefreshService
{

    @Resource
    private JwtConfig jwtConfig;

    @Resource
    private AuthSessionCacheUtil authSessionCacheUtil;

    /**
     * 初始化 token（首次获取）
     *
     * @param openId     用户 openId
     * @param unionId    用户 unionId（可能为 null）
     * @param sessionKey 微信 sessionKey（存 Redis，不放 JWT）
     * @return TokenAuthVo 包含 token 和过期秒数
     */
    public TokenAuthVo initToken(String openId, String unionId, String sessionKey) {
        // 1. 生成 JWT
        String token = jwtConfig.generateToken(openId, unionId);

        // 2. 创建 Redis TokenMeta
        long now = System.currentTimeMillis();
        TokenMeta meta = new TokenMeta();
        meta.setOpenId(openId);
        meta.setUnionId(unionId);
        meta.setSessionKey(sessionKey);
        meta.setCreateTime(now);
        meta.setLastAccessTime(now);
        authSessionCacheUtil.saveTokenMeta(meta);

        // 3. 返回
        TokenAuthVo vo = new TokenAuthVo();
        vo.setToken(token);
        vo.setExpiresIn(jwtConfig.getExpire());
        log.info("初始化 token: openId={}", openId);
        return vo;
    }

    /**
     * 刷新过期 token（滑动窗口内签发新 JWT，继承 ProxySession 状态）
     *
     * @param oldToken      过期的旧 token（标准 Authorization header 格式）
     * @param newSessionKey 新的 sessionKey（可为 null 则沿用旧的）
     * @param newUnionId    新的 unionId（可为 null 则沿用旧的）
     * @return TokenAuthVo
     * @throws IllegalStateException 如果不可刷新
     */
    public TokenAuthVo refreshToken(String oldToken, String newSessionKey, String newUnionId) {
        // 1. 提取 openId
        String openId = jwtConfig.getOpenIdFromToken(oldToken);
        if (!StringUtils.hasText(openId)) {
            throw new IllegalStateException("无法从旧 token 提取 openId");
        }

        // 2. 检查滑动窗口
        if (!authSessionCacheUtil.isRefreshable(openId)) {
            throw new IllegalStateException("会话已过期（超过24小时未活跃），请重新登录");
        }

        // 3. 获取旧的 TokenMeta
        TokenMeta meta = authSessionCacheUtil.getTokenMeta(openId);
        if (meta == null) {
            throw new IllegalStateException("Redis 中无 TokenMeta 数据，请重新登录");
        }

        // 4. 更新 sessionKey 和 unionId（如果有新的）
        if (StringUtils.hasText(newSessionKey)) {
            meta.setSessionKey(newSessionKey);
        }
        String unionId = StringUtils.hasText(newUnionId) ? newUnionId : meta.getUnionId();
        meta.setUnionId(unionId);
        meta.setLastAccessTime(System.currentTimeMillis());

        // 5. 签发新 JWT + 更新 Redis
        String newToken = jwtConfig.generateToken(openId, unionId);
        authSessionCacheUtil.saveTokenMeta(meta);

        TokenAuthVo vo = new TokenAuthVo();
        vo.setToken(newToken);
        vo.setExpiresIn(jwtConfig.getExpire());
        log.info("刷新 token: openId={}", openId);
        return vo;
    }

    /**
     * 判断是否可刷新
     */
    public boolean isRefreshable(String openId) {
        return authSessionCacheUtil.isRefreshable(openId);
    }

    /**
     * 更新 lastAccessTime（每次有效请求时由 Interceptor 调用）
     */
    public void touch(String openId) {
        authSessionCacheUtil.touchTokenMeta(openId);
    }

    /**
     * 获取 sessionKey（需要解密微信敏感数据时使用）
     */
    public String getSessionKey(String openId) {
        TokenMeta meta = authSessionCacheUtil.getTokenMeta(openId);
        return meta != null ? meta.getSessionKey() : null;
    }
}