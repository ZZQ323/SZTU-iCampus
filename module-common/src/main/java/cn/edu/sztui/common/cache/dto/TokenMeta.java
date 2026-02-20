package cn.edu.sztui.common.cache.dto;

import lombok.Data;
import java.io.Serializable;

/**
 * Token 元数据 —— 存储在 Redis 中（base 服务）
 * <p>
 * key: icampus:token-meta:{openId}
 * TTL: 25小时
 * <p>
 * 职责：
 * 1. 保存 sessionKey（敏感信息不放 JWT，只存 Redis）
 * 2. 记录 lastAccessTime 实现滑动窗口刷新（< 24h 可刷新过期 token）
 */
@Data
public class TokenMeta implements Serializable {
    private static final long serialVersionUID = 1L;

    private String openId;
    private String unionId;

    /** 微信 sessionKey —— 用于解密微信敏感数据，只存 Redis，不放 JWT */
    private String sessionKey;

    /** 首次创建时间（ms） */
    private long createTime;

    /** 最后访问时间（ms）—— 用于滑动窗口：< 24h 允许刷新 token */
    private long lastAccessTime;
}