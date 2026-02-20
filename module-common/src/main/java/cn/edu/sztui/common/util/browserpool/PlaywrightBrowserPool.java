package cn.edu.sztui.common.util.browserpool;

import cn.edu.sztui.common.util.exception.BusinessException;
import com.microsoft.playwright.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Playwright 浏览器池（修复版）
 * <p>
 * 修复：BusinessException 不应被包装成 RuntimeException
 */
@Slf4j
@Component
public class PlaywrightBrowserPool {

    private Playwright playwright;
    private Browser browser;
    private BlockingQueue<BrowserContext> contextPool;

    @Value("${playwright.pool.pool-size:5}")
    private int poolSize;

    /** 普通接口超时（秒） */
    @Value("${playwright.pool.timeout-request-seconds:15}")
    private int defaultTimeoutSeconds;

    /** 慢接口超时（秒） */
    @Value("${playwright.pool.timeout-slow-request-seconds:90}")
    private int slowTimeoutSeconds;

    @Value("${playwright.pool.timeout-poll-seconds:10}")
    private int timeoutPollSeconds;

    @Value("${playwright.pool.headless:true}")
    private boolean headless;

    @Value("${playwright.pool.ignore-https-errors:true}")
    private boolean ignoreHttpsErrors;

    @Value("${playwright.pool.browser-type:chromium}")
    private String browserType;

    @Value("${playwright.pool.viewport-width:1280}")
    private Integer viewportWidth;

    @Value("${playwright.pool.viewport-height:720}")
    private Integer viewportHeight;

    @Value("${playwright.pool.user-agent:}")
    private String userAgent;

    @PostConstruct
    public void init() {
        playwright = Playwright.create();
        BrowserType browserTypeObj =
                switch (browserType.toLowerCase()) {
                    case "firefox" -> playwright.firefox();
                    case "webkit" -> playwright.webkit();
                    default -> playwright.chromium();
                };
        browser = browserTypeObj.launch(new BrowserType.LaunchOptions().setHeadless(headless));
        contextPool = new ArrayBlockingQueue<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            contextPool.offer(createNewContext());
        }
        log.info("Playwright 浏览器池初始化完成，池大小: {}, 默认超时: {}s, 慢接口超时: {}s",
                poolSize, defaultTimeoutSeconds, slowTimeoutSeconds);
    }

    private BrowserContext createNewContext() {
        Browser.NewContextOptions options = new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(ignoreHttpsErrors);
        if (viewportWidth != null && viewportHeight != null)
            options.setViewportSize(viewportWidth, viewportHeight);
        if (userAgent != null && !userAgent.isEmpty())
            options.setUserAgent(userAgent);
        BrowserContext context = browser.newContext(options);
        context.setDefaultTimeout(defaultTimeoutSeconds * 1000);
        return context;
    }

    public BrowserContext acquireContext() throws InterruptedException {
        BrowserContext context = contextPool.poll(timeoutPollSeconds, TimeUnit.SECONDS);
        if (context == null)
            throw new RuntimeException("获取浏览器上下文超时");
        return context;
    }

    public void releaseContext(BrowserContext context) {
        if (context == null)
            return;
        try {
            context.setDefaultTimeout(defaultTimeoutSeconds * 1000);
            context.clearCookies();
            context.pages().forEach(Page::close);
            if (!contextPool.offer(context))
                context.close();
        } catch (Exception e) {
            try {
                context.close();
            } catch (Exception ignored) {
            }
            contextPool.offer(createNewContext());
        }
    }

    public <T> T executeWithContext(ContextAction<T> action) {
        return executeWithContext(action, defaultTimeoutSeconds);
    }

    /**
     * 使用自定义超时执行操作
     * <p>
     * 【修复】BusinessException 直接抛出，不包装成 RuntimeException
     */
    public <T> T executeWithContext(ContextAction<T> action, int timeoutSeconds) {
        BrowserContext context = null;
        try {
            context = acquireContext();
            context.setDefaultTimeout(timeoutSeconds * 1000);
            log.debug("执行 Playwright 操作，超时设置: {}s", timeoutSeconds);
            return action.execute(context);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("获取浏览器上下文被中断", e);
        } catch (BusinessException e) {
            // 【修复】BusinessException 直接抛出，让全局异常处理器处理
            throw e;
        } catch (RuntimeException e) {
            // RuntimeException 也直接抛出
            throw e;
        } catch (Exception e) {
            // 其他受检异常包装成 RuntimeException
            throw new RuntimeException(e);
        } finally {
            releaseContext(context);
        }
    }

    public int getDefaultTimeoutSeconds() {
        return defaultTimeoutSeconds;
    }

    public int getSlowTimeoutSeconds() {
        return slowTimeoutSeconds;
    }

    @PreDestroy
    public void destroy() {
        BrowserContext context;
        while ((context = contextPool.poll()) != null) {
            try {
                context.close();
            } catch (Exception ignored) {
            }
        }
        if (browser != null)
            browser.close();
        if (playwright != null)
            playwright.close();
        log.info("Playwright 浏览器池已销毁");
    }

    @FunctionalInterface
    public interface ContextAction<T> {
        T execute(BrowserContext context) throws Exception;
    }
}