package cn.edu.sztui.base.infrastructure.util.parser;

public class URLPraser {

    // 辅助方法：提取 Origin
    public static String extractOrigin(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            return u.getProtocol() + "://" + u.getHost() +
                    (u.getPort() != -1 ? ":" + u.getPort() : "");
        } catch (Exception e) {
            return "";
        }
    }
}
