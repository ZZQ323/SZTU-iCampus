package cn.edu.sztui.common.util.smarthttp;

/**
 * 智能 HTTP 客户端异常
 */
public class SmartHttpException extends RuntimeException {
    
    private final int statusCode;
    private final boolean retryable;
    
    public SmartHttpException(String message) {
        super(message);
        this.statusCode = 0;
        this.retryable = true;
    }
    
    public SmartHttpException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.retryable = true;
    }
    
    public SmartHttpException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
        this.retryable = statusCode >= 500 || statusCode == 0;
    }
    
    public SmartHttpException(String message, int statusCode, boolean retryable) {
        super(message);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }
    
    public int getStatusCode() {
        return statusCode;
    }
    
    public boolean isRetryable() {
        return retryable;
    }
    
    /**
     * 超时异常
     */
    public static SmartHttpException timeout(String url) {
        return new SmartHttpException("请求超时: " + url, 504, true);
    }
    
    /**
     * 连接失败异常
     */
    public static SmartHttpException connectionFailed(String url, Throwable cause) {
        return new SmartHttpException("连接失败: " + url, cause);
    }
    
    /**
     * 重定向次数超限
     */
    public static SmartHttpException tooManyRedirects(int count) {
        return new SmartHttpException("重定向次数超过限制: " + count, 0, false);
    }
}
