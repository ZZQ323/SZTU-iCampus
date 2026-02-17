package cn.edu.sztui.common.util.jwt;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 配置和工具类（common 包）
 * <p>
 * 设计要点：
 * 1. JWT claims 只放 openId、unionId，不放 sessionKey（敏感信息存 Redis）
 * 2. claim key 用小写（openid / unionid），与 JwtAuthFilter 读取一致
 * 3. expired 验证结果也携带 claims，方便提取 openId 用于刷新判断
 */
@Data
@Component
@ConfigurationProperties(prefix = "jwt.wxmini")
public class JwtConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);
    private String secret;
    private Long expire;          // 默认过期时间（秒），建议 14400（4小时）
    private String tokenPrefix;   // "Bearer "
    private String header;        // "Authorization"
    private SecretKey secretKey;

    @PostConstruct
    public void init() {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    // ==================== 生成 Token ====================

    /**
     * 生成 Token（使用默认过期时间）
     */
    public String generateToken(String openId, String unionId) {
        return generateToken(openId, unionId, expire);
    }

    /**
     * 生成 Token（指定过期时间）
     * <p>
     * claims 中只放 openid 和 unionid（小写 key，与 Filter 一致）。
     * 不放 sessionKey，避免客户端解码获得敏感信息。
     */
    public String generateToken(String openId, String unionId, long expireSeconds) {
        Date now = new Date();
        Date expireDate = new Date(now.getTime() + expireSeconds * 1000);

        Map<String, Object> claims = new HashMap<>();
        claims.put("openid", openId);          // 小写，与 Filter 中 claims.get("openid") 一致
        claims.put("unionid", unionId);        // 可能为 null
        claims.put("createdTime", now.getTime());

        return Jwts.builder()
                .setClaims(claims)
                .setSubject(openId)                            // subject = openId
                .setIssuedAt(now)
                .setExpiration(expireDate)
                .signWith(secretKey, SignatureAlgorithm.HS256)
                .compact();
    }

    // ==================== 解析 & 验证 ====================

    public Claims parseToken(String token) {
        token = stripPrefix(token);
        return Jwts.parserBuilder()
                .setSigningKey(secretKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /**
     * 验证 Token
     * <p>
     * 注意：expired 结果也携带 claims（从 ExpiredJwtException 中提取），
     * 这样调用方可以提取 openId 用于刷新判断。
     */
    public TokenValidationResult validateToken(String token) {
        try {
            Claims claims = parseToken(token);
            return TokenValidationResult.success(claims);
        } catch (ExpiredJwtException e) {
            log.warn("Token 已过期: {}", e.getMessage());
            return TokenValidationResult.expired(e.getClaims());
        } catch (SignatureException e) {
            log.warn("Token 签名无效: {}", e.getMessage());
            return TokenValidationResult.invalid("签名无效");
        } catch (MalformedJwtException e) {
            log.warn("Token 格式错误: {}", e.getMessage());
            return TokenValidationResult.invalid("格式错误");
        } catch (Exception e) {
            log.warn("Token 验证失败: {}", e.getMessage());
            return TokenValidationResult.invalid("验证失败");
        }
    }

    /**
     * 从 Token 中提取 openId（即使 token 已过期也能提取）
     */
    public String getOpenIdFromToken(String token) {
        try {
            Claims claims = parseToken(token);
            return claims.getSubject();
        } catch (ExpiredJwtException e) {
            return e.getClaims().getSubject();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 判断 Token 是否即将过期
     */
    public boolean isTokenExpiringSoon(String token, long minSeconds) {
        try {
            Claims claims = parseToken(token);
            long remaining = claims.getExpiration().getTime() - System.currentTimeMillis();
            return remaining < minSeconds * 1000;
        } catch (Exception e) {
            return true;
        }
    }

    private String stripPrefix(String token) {
        if (token != null && tokenPrefix != null && token.startsWith(tokenPrefix)) {
            return token.substring(tokenPrefix.length()).trim();
        }
        return token;
    }

    // ==================== 验证结果 ====================

    public static class TokenValidationResult {
        private boolean valid;
        private boolean expired;
        private String message;
        private Claims claims;

        public static TokenValidationResult success(Claims claims) {
            TokenValidationResult r = new TokenValidationResult();
            r.valid = true;
            r.expired = false;
            r.claims = claims;
            return r;
        }

        /**
         * 过期但仍携带 claims
         * Filter 中用于返回 401；
         * TokenRefreshService 中用于提取 openId 判断是否可刷新。
         */
        public static TokenValidationResult expired(Claims claims) {
            TokenValidationResult r = new TokenValidationResult();
            r.valid = false;
            r.expired = true;
            r.claims = claims;
            r.message = "Token已过期";
            return r;
        }

        public static TokenValidationResult invalid(String message) {
            TokenValidationResult r = new TokenValidationResult();
            r.valid = false;
            r.expired = false;
            r.message = message;
            return r;
        }

        public boolean isValid() { return valid; }
        public boolean isExpired() { return expired; }
        public String getMessage() { return message; }
        public Claims getClaims() { return claims; }
    }
}