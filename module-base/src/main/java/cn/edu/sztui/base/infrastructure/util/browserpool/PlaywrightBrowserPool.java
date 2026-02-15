package cn.edu.sztui.base.infrastructure.util.browserpool;

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
 * 代理池
 */
@Slf4j
@Component
public class PlaywrightBrowserPool{

    private Playwright playwright;
    private Browser browser;
    private BlockingQueue<BrowserContext> contextPool;

    @Value("${playwright.pool.pool-size}")
    private int poolSize;

    @Value("${playwright.pool.timeout-reqest-duration}")
    private int timeoutReqSeconds;

    @Value("${playwright.pool.timeout-poll-seconds}")
    private int timeoutPollSeconds;

    @Value("${playwright.pool.headless}")
    private boolean headless;

    @Value("${playwright.pool.ignore-https-errors}")
    private boolean ignoreHttpsErrors;

    @Value("${playwright.pool.browser-type}")
    private String browserType;

    @Value("${playwright.pool.viewport-width}")
    private Integer viewportWidth;

    @Value("${playwright.pool.viewport-height}")
    private Integer viewportHeight;

    @Value("${playwright.pool.user-agent}")
    private String userAgent;

    @PostConstruct
    public void init()
    {
        playwright = Playwright.create();
        // 根据配置选择浏览器类型
        BrowserType browserTypeObj =
            switch (browserType.toLowerCase()) {
                case "firefox" -> playwright.firefox();
                case "webkit" -> playwright.webkit();
                default -> playwright.chromium();
            };
        browser = browserTypeObj.launch(new BrowserType.LaunchOptions().setHeadless(headless));
        // 初始化 Context 池
        contextPool = new ArrayBlockingQueue<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            contextPool.offer(createNewContext());
        }
        log.info("Playwright 浏览器池初始化完成，池大小: " + poolSize);
    }

    /**
     * 创建新的浏览器上下文
     */
    private BrowserContext createNewContext() {
        Browser.NewContextOptions options = new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(ignoreHttpsErrors);
        // 设置视口大小
        if (viewportWidth != null && viewportHeight != null)
            options.setViewportSize(viewportWidth, viewportHeight);
        // 设置 User Agent
        if (userAgent != null && !userAgent.isEmpty())
            options.setUserAgent(userAgent);
        BrowserContext context = browser.newContext(options);
        context.setDefaultTimeout(timeoutReqSeconds*1000);
        return context;
    }

    /**
     * 获取浏览器上下文
     */
    public BrowserContext acquireContext() throws InterruptedException {
        BrowserContext context = contextPool.poll(timeoutPollSeconds, TimeUnit.SECONDS);
        if (context == null)
            // 一定要报错么
            throw new RuntimeException("获取浏览器上下文超时");
        return context;
    }

    /**
     * 释放浏览器上下文（清空并放回池中）
     */
    public void releaseContext(BrowserContext context) {
        if (context == null)
            return;
        try {
            // 清空 cookies
            context.clearCookies();
            // 关闭所有页面
            context.pages().forEach(Page::close);
            // 放回池中
            if (!contextPool.offer(context))
                // 如果池满了，关闭这个 context
                context.close();
        } catch (Exception e) {
            // 如果清理失败，关闭并创建新的
            try {
                context.close();
            } catch (Exception ignored) {}
            contextPool.offer(createNewContext());
        }
    }

    /**
     * 使用浏览器上下文执行操作，核心逻辑
     */
    public <T> T executeWithContext(ContextAction<T> action) {
        BrowserContext context = null;
        try {
            context = acquireContext();
            return action.execute(context);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("获取浏览器上下文被中断", e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            releaseContext(context);
        }
    }

    @PreDestroy
    public void destroy() {
        // 清理所有 context
        BrowserContext context;
        while ((context = contextPool.poll()) != null) {
            try {
                context.close();
            } catch (Exception ignored) {}
        }
        // 关闭 browser 和 playwright
        if (browser != null)
            browser.close();
        if (playwright != null)
            playwright.close();
        log.info("Playwright 浏览器池已销毁");
    }

    /**
     * 函数式接口，用于执行操作
     */
    @FunctionalInterface
    public interface ContextAction<T> {
        T execute(BrowserContext context) throws Exception;
    }
}