package cn.edu.sztui.stream.application.external.engine;

import org.springframework.util.StringUtils;

/**
 * 文章 URL 解析器
 * <p>
 * 统一处理学校网站三种 URL 形式：
 * <ul>
 *   <li>绝对路径：https://tusports.sztu.edu.cn/info/1015/1914.htm</li>
 *   <li>相对路径：../../info/1040/4091.htm</li>
 *   <li>外链：微信公众号、bysjy.com.cn 等</li>
 * </ul>
 */
public final class ArticleUrlResolver {

    private ArticleUrlResolver() {
    }

    /**
     * 外链前缀标记
     */
    public static final String EXTERNAL_PREFIX = "EXTERNAL:";

    /**
     * 将 href 解析为完整的绝对 URL
     *
     * @param href    原始链接（从 HTML 中提取）
     * @param baseUrl 当前源的 baseUrl（如 https://jw.sztu.edu.cn）
     * @return 绝对 URL，外链以 "EXTERNAL:" 前缀标记
     */
    public static String resolve(String href, String baseUrl) {
        if (!StringUtils.hasText(href)) return null;

        href = href.trim();

        // 1. 已经是绝对路径
        if (href.startsWith("http://") || href.startsWith("https://")) {
            if (isExternalLink(href)) {
                return EXTERNAL_PREFIX + href;
            }
            return href;
        }

        // 2. 协议相对
        if (href.startsWith("//")) {
            String full = "https:" + href;
            return isExternalLink(full) ? EXTERNAL_PREFIX + full : full;
        }

        // 3. 相对路径 ../../info/1040/4091.htm → /info/1040/4091.htm
        if (href.startsWith("../")) {
            String cleaned = href;
            while (cleaned.startsWith("../")) {
                cleaned = cleaned.substring(3);
            }
            return baseUrl + "/" + cleaned;
        }

        // 4. 绝对路径 /info/xxx
        if (href.startsWith("/")) {
            return baseUrl + href;
        }

        // 5. 裸相对路径 info/xxx
        return baseUrl + "/" + href;
    }

    /**
     * 判断是否为外部链接
     */
    public static boolean isExternalLink(String url) {
        if (url == null) return false;
        // 微信公众号
        if (url.contains("mp.weixin.qq.com")) return true;
        // 就业平台
        if (url.contains("bysjy.com.cn")) return true;
        // 非 sztu 域名
        if (!url.contains("sztu.edu.cn")) return true;
        return false;
    }

    /**
     * 从标准 URL 中提取 ID
     * <p>
     * 支持格式：/info/{category}/{id}.htm
     */
    public static String extractId(String url) {
        if (url == null) return null;
        // 去掉外链前缀
        if (url.startsWith(EXTERNAL_PREFIX)) return null;

        // 匹配 /info/xxxx/1234.htm 或 /1234.htm
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash < 0) return null;

        String filename = url.substring(lastSlash + 1);
        int dotPos = filename.lastIndexOf('.');
        if (dotPos > 0) {
            return filename.substring(0, dotPos);
        }
        return filename;
    }

    /**
     * 从标准 URL 中提取 category
     * <p>
     * /info/1018/4567.htm → "1018"
     */
    public static String extractCategory(String url) {
        if (url == null) return null;
        if (url.startsWith(EXTERNAL_PREFIX)) return null;

        int infoIdx = url.indexOf("/info/");
        if (infoIdx < 0) return null;

        String after = url.substring(infoIdx + 6); // 去掉 "/info/"
        int slash = after.indexOf('/');
        if (slash > 0) {
            return after.substring(0, slash);
        }
        return null;
    }
}