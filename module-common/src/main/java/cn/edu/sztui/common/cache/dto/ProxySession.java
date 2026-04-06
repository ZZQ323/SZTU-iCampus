package cn.edu.sztui.common.cache.dto;

import lombok.Data;
import java.io.Serializable;
import java.util.List;

/**
 * 学校后端代理会话 —— 存储在 Redis 中（base 服务）
 * <p>
 * key: icampus:proxy-session:{userId}
 * TTL: 25小时（跟随 TokenMeta）
 */
@Data
public class ProxySession implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 关联的 userId（学号） */
    private String userId;

    /** 历史登录过的学号列表 */
    private List<String> userIds;

    /** 学校后端的 cookies（JSON 格式） */
    private String cookiesJson;

    /** session 创建时间（ms） */
    private long createTime;

    /** 最后更新时间（ms） */
    private long lastUpdateTime;

    /** 是否已登录学校后端 */
    private boolean schoolLoggedIn;
}