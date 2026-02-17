package cn.edu.sztui.common.util.bean;

import lombok.Data;

import java.io.Serializable;

@Data
public class TokenMessage implements Serializable
{
    private static final long serialVersionUID = 1L;
    private String openId;
    private String unionId;
    private String sessionKey;       // 兼容保留；JWT 来源时为 null，需要时从 Redis 取
    private Long loginTime;          // JWT iat
    private Long expireTime;         // JWT exp
}
