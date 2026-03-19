package cn.edu.sztui.stream.application.external.engine;

import org.springframework.util.StringUtils;

/**
 * 文章 URL 解析器
 * <p>
 * ⭐ 更新：放宽外链检测，支持政府/媒体外链（就业网场景）
 */
public final class ArticleUrlResolver {

    private ArticleUrlResolver() {
    }

    public static final String EXTERNAL_PREFIX = "EXTERNAL:";

    /**
     * 将 href 解析为绝对 URL，外链加 EXTERNAL: 前缀
     */
    public static String resolve(String href, String baseUrl) {
        if (!StringUtils.hasText(href)) return null;
        href = href.trim();

        // 1. 绝对路径
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return isExternalLink(href) ? EXTERNAL_PREFIX + href : href;
        }

        // 2. 协议相对
        if (href.startsWith("//")) {
            String full = "https:" + href;
            return isExternalLink(full) ? EXTERNAL_PREFIX + full : full;
        }

        // 3. ../xxx → 去掉 ../ 拼 baseUrl
        if (href.startsWith("../")) {
            String cleaned = href;
            while (cleaned.startsWith("../")) {
                cleaned = cleaned.substring(3);
            }
            return baseUrl + "/" + cleaned;
        }

        // 4. /xxx → baseUrl + /xxx
        if (href.startsWith("/")) {
            return baseUrl + href;
        }

        // 5. 裸相对路径
        return baseUrl + "/" + href;
    }

    /**
     * 判断是否为外部链接（非 sztu.edu.cn）
     */
    public static boolean isExternalLink(String url) {
        if (url == null) return false;
        // 微信公众号
        if (url.contains("mp.weixin.qq.com")) return true;
        // 就业平台
        if (url.contains("bysjy.com.cn")) return true;
        // 非 sztu 域名 → 外链
        if (!url.contains("sztu.edu.cn")) return true;
        return false;
    }

    /**
     * 从 URL 提取 ID（info/{cat}/{id}.htm → id）
     */
    public static String extractId(String url) {
        if (url == null || url.startsWith(EXTERNAL_PREFIX)) return null;
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash < 0) return null;
        String filename = url.substring(lastSlash + 1);
        int dotPos = filename.lastIndexOf('.');
        if (dotPos > 0) return filename.substring(0, dotPos);
        return filename;
    }

    /**
     * 从 URL 提取 categoryCode（info/{cat}/{id}.htm → cat）
     */
    public static String extractCategory(String url) {
        if (url == null || url.startsWith(EXTERNAL_PREFIX)) return null;
        int infoIdx = url.indexOf("/info/");
        if (infoIdx < 0) {
            // 也匹配相对路径 info/1020/xxx.htm
            infoIdx = url.indexOf("info/");
            if (infoIdx < 0) return null;
            String after = url.substring(infoIdx + 5);
            int slash = after.indexOf('/');
            return slash > 0 ? after.substring(0, slash) : null;
        }
        String after = url.substring(infoIdx + 6);
        int slash = after.indexOf('/');
        return slash > 0 ? after.substring(0, slash) : null;
    }
}