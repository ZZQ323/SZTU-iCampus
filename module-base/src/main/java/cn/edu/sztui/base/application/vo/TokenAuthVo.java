package cn.edu.sztui.base.application.vo;

import lombok.Data;

/**
 * Token 认证响应 VO
 */
@Data
public class TokenAuthVo {
    /** JWT token 字符串 */
    private String token;

    /** token 过期时间（秒），前端可以用来设置计时器 */
    private Long expiresIn;
}