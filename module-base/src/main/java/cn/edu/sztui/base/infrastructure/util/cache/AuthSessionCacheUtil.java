package cn.edu.sztui.base.infrastructure.util.cache;

import cn.edu.sztui.base.infrastructure.convertor.CookieConverter;
import cn.edu.sztui.base.infrastructure.util.cache.dto.ProxySession;
import cn.edu.sztui.common.cache.util.CacheUtil;
import cn.edu.sztui.common.util.enums.ResultCodeEnum;
import cn.edu.sztui.common.util.enums.SysReturnCode;
import cn.edu.sztui.common.util.exception.BusinessException;
import com.alibaba.fastjson2.JSON;
import com.microsoft.playwright.options.Cookie;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * 代理会话缓存工具
 * 存储结构：
 * - auth:init:{wxId} -> seesion ，所有的信息，状态全部储存在 proxy
 */
@Slf4j
@Component
public class AuthSessionCacheUtil {
    private static final String UNIONID_SESSION_KEY = "auth:miniapp:";
    // 所有会话共享同一个 TTL（过期时间），每次 hset 单个会话时设置的过期时间会应用到整个 Hash
    // 所以不设置任何过期时间
     private static final long MACHINE_SESSION_EXPIRE = -1;
     private static final long LOGIN_SESSION_EXPIRE = -1;
    // 机器会话过期时间：30分钟
    // private static final long MACHINE_SESSION_EXPIRE = -1;
    // 登录会话过期时间：1小时
    // private static final long LOGIN_SESSION_EXPIRE = -1;

    @Resource
    private CacheUtil cacheUtil;

    /**
     * 获取会话
     * @param wxId
     * @return
     */
    public ProxySession getSession(String wxId) {
        if ( !StringUtils.hasText(wxId) ) return null;
        Object obj = cacheUtil.hget(UNIONID_SESSION_KEY, wxId);
        if (obj == null) return null;
        return JSON.parseObject(obj.toString(), ProxySession.class);
    }

    /**
     * 获取所有会话
     * @return Map<wxId, ProxySession>
     */
    public Map<String, ProxySession> getAllSessions() {
        Map<Object, Object> rawMap = cacheUtil.hmget(UNIONID_SESSION_KEY);
        if (rawMap == null || rawMap.isEmpty()) return Collections.emptyMap();
        Map<String, ProxySession> result = new HashMap<>();
        rawMap.forEach((key, value) -> {
            String wxId = key.toString();
            ProxySession session = JSON.parseObject(value.toString(), ProxySession.class);
            result.put(wxId, session);
        });
        return result;
    }

    /**
     * <p>只更新基础信息</p>
     * <p>登录前，无账号，只有账号在小程序中的 wxId</p>
     *
     * @param wxId
     * @param cookies
     */
    public boolean saveOrUpdateSession(String wxId, List<Cookie> cookies) {
        ProxySession session = getSession(wxId);
        if (StringUtils.hasText(wxId) && !CollectionUtils.isEmpty(cookies)) {
            if(Objects.isNull(session)){
                // 新创建seesion
                session = new ProxySession();
                session.setWxCode(wxId);
                session.setCreateTime(System.currentTimeMillis());  // 获取的系统时间就足够了吗？
                session.setUserIds(Collections.emptyList());        // 方便执行添加操作
            }
            session.setCookiesJson(CookieConverter.toCookieStrings(cookies));
            session.setLastUpdateTime(System.currentTimeMillis());  // 自动覆盖，管理expire
            cacheUtil.hset(UNIONID_SESSION_KEY,wxId, JSON.toJSONString(session), MACHINE_SESSION_EXPIRE);
            log.info("保存小程序wxId: {}，session：{}", wxId, session);
            return true;
        }return false;
    }

    /**
     * 登录更新登录状态
     * @param wxId
     * @param userId
     * @param newCookies
     */
    public boolean sessionLoginBind(String wxId, String userId, List<Cookie> newCookies) {
        ProxySession session = getSession(wxId);
        if ( Objects.isNull(session) ) {
            // 前端通过每次打开都进行初始化的方式进行检查，这里仅做意外的检测
            throw new BusinessException(SysReturnCode.BASE_PROXY.getCode(), "无法获取初始化会话！！！！", ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode());
        }
        session.getUserIds().add(userId);
        session.setCookiesJson(CookieConverter.toCookieStrings(newCookies));
        session.setLastUpdateTime(System.currentTimeMillis());
        session.setLoggedIn(true);
        // 更新机器会话（延长过期时间）
        cacheUtil.hset(UNIONID_SESSION_KEY,wxId, JSON.toJSONString(session), LOGIN_SESSION_EXPIRE);
        log.info("用户 {} 绑定到小程序wxId {}", userId, wxId);
        return true;
    }

    /**
     * 检查机器会话是否有效
     * @param wxId
     * @return
     */
    public boolean hasSession(String wxId) {
        return cacheUtil.hHasKey(UNIONID_SESSION_KEY, wxId);
    }

    /**
     * 检查是否已登录
     * @param wxId
     * @return
     */
    public boolean isLoggedIn(String wxId) {
        ProxySession session = getSession(wxId);
        return !Objects.isNull(session) && session.isLoggedIn();
    }

    /**
     * 删除会话（登出）
     */
    public void sessionLogoutBind(String wxId) {
        ProxySession session = getSession(wxId);
        session.setLastUpdateTime(System.currentTimeMillis());
        session.setLoggedIn(true);
        cacheUtil.hset(UNIONID_SESSION_KEY,wxId, JSON.toJSONString(session));
    }
}
