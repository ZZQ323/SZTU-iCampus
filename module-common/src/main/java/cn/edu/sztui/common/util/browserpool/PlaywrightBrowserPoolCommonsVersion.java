package cn.edu.sztui.common.util.browserpool;

import cn.edu.sztui.common.util.exception.BusinessException;
import com.microsoft.playwright.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Playwright 浏览器池（Apache Commons Pool2 版本）
 *
 * 特性：
 * <ul>
 *  <li>智能扩缩容：需要时自动创建，空闲时自动销毁</li>
 *  <li>对象验证：借用/归还时验证有效性</li>
 *  <li>空闲驱逐：定期清理长期空闲的对象</li>
 *  <li>超时控制：可配置的超时时间</li>
 * </ul>
 */
@Slf4j
@Component
public class PlaywrightBrowserPoolCommonsVersion {
    private Playwright playwright;
    private Browser browser;
    private GenericObjectPool<BrowserContext> contextPool;

    // ==================== 统计信息 ====================
    private final AtomicInteger createdCount = new AtomicInteger(0);
    private final AtomicInteger destroyedCount = new AtomicInteger(0);

    // ==================== 配置项 ====================

    @Value("${playwright.pool.pool-size:20}")
    private int maxTotal;

    @Value("${playwright.pool.max-idle:10}")
    private int maxIdle;

    @Value("${playwright.pool.min-idle:2}")
    private int minIdle;

    @Value("${playwright.pool.timeout-request-seconds:15}")
    private int defaultTimeoutSeconds;

    @Value("${playwright.pool.timeout-slow-request-seconds:90}")
    private int slowTimeoutSeconds;

    @Value("${playwright.pool.timeout-poll-seconds:30}")
    private int timeoutPollSeconds;

    @Value("${playwright.pool.headless:true}")
    private boolean headless;

    @Value("${playwright.pool.ignore-https-errors:true}")
    private boolean ignoreHttpsErrors;

    @Value("${playwright.pool.browser-type:chromium}")
    private String browserType;

    @Value("${playwright.pool.viewport-width:#{null}}")
    private Integer viewportWidth;

    @Value("${playwright.pool.viewport-height:#{null}}")
    private Integer viewportHeight;

    @Value("${playwright.pool.user-agent:}")
    private String userAgent;

    @PostConstruct
    public void init() {
        // 1. 初始化 Playwright 和 Browser
        playwright = Playwright.create();
        BrowserType browserTypeObj = switch (browserType.toLowerCase()) {
            case "firefox" -> playwright.firefox();
            case "webkit" -> playwright.webkit();
            default -> playwright.chromium();
        };
        browser = browserTypeObj.launch(new BrowserType.LaunchOptions().setHeadless(headless));

        // 2. 配置对象池
        GenericObjectPoolConfig<BrowserContext> config = new GenericObjectPoolConfig<>();

        // 容量配置
        config.setMaxTotal(maxTotal);           // 最大对象数
        config.setMaxIdle(maxIdle);             // 最大空闲数（超过会被销毁）
        config.setMinIdle(minIdle);             // 最小空闲数（低于会自动创建）

        // 等待配置
        config.setMaxWait(Duration.ofSeconds(timeoutPollSeconds));
        config.setBlockWhenExhausted(true);     // 池满时阻塞等待

        // 验证配置
        config.setTestOnBorrow(true);           // 借用时验证
        config.setTestOnReturn(true);           // 归还时验证
        config.setTestWhileIdle(true);          // 空闲时验证

        // 驱逐配置（自动缩容）
        config.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));  // 每30秒检查一次
        config.setMinEvictableIdleDuration(Duration.ofMinutes(5));  // 空闲5分钟可被驱逐
        config.setSoftMinEvictableIdleDuration(Duration.ofMinutes(2)); // 软驱逐时间
        config.setNumTestsPerEvictionRun(3);    // 每次检查3个对象

        // JMX 监控（可选）
        config.setJmxEnabled(false);

        // 3. 创建对象池
        contextPool = new GenericObjectPool<>(new BrowserContextFactory(), config);

        // 4. 预热：创建最小空闲数量的对象
        try {
            for (int i = 0; i < minIdle; i++) {
                contextPool.addObject();
            }
        } catch (Exception e) {
            log.warn("预热对象池失败: {}", e.getMessage());
        }

        log.info("Playwright 浏览器池初始化完成 - maxTotal: {}, maxIdle: {}, minIdle: {}, " +
                        "defaultTimeout: {}s, slowTimeout: {}s, pollTimeout: {}s",
                maxTotal, maxIdle, minIdle, defaultTimeoutSeconds, slowTimeoutSeconds, timeoutPollSeconds);
    }

    /**
     * BrowserContext 工厂类
     */
    private class BrowserContextFactory extends BasePooledObjectFactory<BrowserContext> {

        @Override
        public BrowserContext create() {
            Browser.NewContextOptions options = new Browser.NewContextOptions()
                    .setIgnoreHTTPSErrors(ignoreHttpsErrors);

            if (viewportWidth != null && viewportHeight != null) {
                options.setViewportSize(viewportWidth, viewportHeight);
            }
            if (userAgent != null && !userAgent.isEmpty()) {
                options.setUserAgent(userAgent);
            }

            BrowserContext context = browser.newContext(options);
            context.setDefaultTimeout(defaultTimeoutSeconds * 1000L);

            int count = createdCount.incrementAndGet();
            log.info("创建新 BrowserContext，累计创建: {}", count);

            return context;
        }

        @Override
        public PooledObject<BrowserContext> wrap(BrowserContext context) {
            return new DefaultPooledObject<>(context);
        }

        /**
         * 激活对象（借用前调用）
         */
        @Override
        public void activateObject(PooledObject<BrowserContext> p) {
            BrowserContext context = p.getObject();
            context.setDefaultTimeout(defaultTimeoutSeconds * 1000L);
        }

        /**
         * 钝化对象（归还前调用）
         */
        @Override
        public void passivateObject(PooledObject<BrowserContext> p) {
            BrowserContext context = p.getObject();
            try {
                // 清理 Cookies
                context.clearCookies();

                // 关闭所有页面
                for (Page page : context.pages()) {
                    try {
                        if (!page.isClosed()) {
                            page.close();
                        }
                    } catch (Exception e) {
                        log.warn("关闭页面失败: {}", e.getMessage());
                    }
                }

                // 重置超时
                context.setDefaultTimeout(defaultTimeoutSeconds * 1000L);

            } catch (Exception e) {
                log.warn("钝化 Context 失败: {}", e.getMessage());
                throw e; // 抛出异常让池销毁这个对象
            }
        }

        /**
         * 验证对象是否可用
         */
        @Override
        public boolean validateObject(PooledObject<BrowserContext> p) {
            try {
                BrowserContext context = p.getObject();
                // 尝试获取 pages 列表来验证 context 是否有效
                context.pages();
                return true;
            } catch (Exception e) {
                log.warn("Context 验证失败: {}", e.getMessage());
                return false;
            }
        }

        /**
         * 销毁对象
         */
        @Override
        public void destroyObject(PooledObject<BrowserContext> p) {
            try {
                BrowserContext context = p.getObject();

                // 先关闭所有页面
                for (Page page : context.pages()) {
                    try {
                        if (!page.isClosed()) {
                            page.close();
                        }
                    } catch (Exception ignored) {
                    }
                }

                // 关闭 context
                context.close();

                int count = destroyedCount.incrementAndGet();
                log.info("销毁 BrowserContext，累计销毁: {}", count);

            } catch (Exception e) {
                log.error("销毁 Context 失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 执行操作（默认超时）
     */
    public <T> T executeWithContext(ContextAction<T> action) {
        return executeWithContext(action, defaultTimeoutSeconds);
    }

    /**
     * 执行操作（自定义超时）
     */
    public <T> T executeWithContext(ContextAction<T> action, int timeoutSeconds) {
        BrowserContext context = null;
        Page page = null;

        try {
            // 从池中借用
            context = contextPool.borrowObject();
            context.setDefaultTimeout(timeoutSeconds * 1000L);

            log.info("借用 Context 成功，当前活跃: {}, 空闲: {}",
                    contextPool.getNumActive(), contextPool.getNumIdle());

            return action.execute(context);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("获取浏览器上下文被中断", e);
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("执行浏览器操作失败: " + e.getMessage(), e);
        } finally {
            // 归还到池中
            if (context != null) {
                try {
                    contextPool.returnObject(context);
                } catch (Exception e) {
                    log.warn("归还 Context 失败: {}", e.getMessage());
                    // 归还失败，尝试使其失效
                    try {
                        contextPool.invalidateObject(context);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    /**
     * 获取池状态信息
     */
    public PoolStats getPoolStats() {
        return new PoolStats(
                contextPool.getNumActive(),
                contextPool.getNumIdle(),
                contextPool.getNumWaiters(),
                createdCount.get(),
                destroyedCount.get(),
                contextPool.getBorrowedCount(),
                contextPool.getReturnedCount()
        );
    }

    /**
     * 打印池状态
     */
    public void logPoolStats() {
        PoolStats stats = getPoolStats();
        log.info("浏览器池状态 - 活跃: {}, 空闲: {}, 等待: {}, " +
                        "累计创建: {}, 累计销毁: {}, 借用次数: {}, 归还次数: {}",
                stats.active, stats.idle, stats.waiters,
                stats.created, stats.destroyed, stats.borrowed, stats.returned);
    }

    public int getDefaultTimeoutSeconds() {
        return defaultTimeoutSeconds;
    }

    public int getSlowTimeoutSeconds() {
        return slowTimeoutSeconds;
    }

    @PreDestroy
    public void destroy() {
        log.info("开始销毁浏览器池...");

        if (contextPool != null) {
            try {
                contextPool.close();
            } catch (Exception e) {
                log.error("关闭对象池失败", e);
            }
        }

        if (browser != null) {
            try {
                browser.close();
            } catch (Exception e) {
                log.error("关闭 Browser 失败", e);
            }
        }

        if (playwright != null) {
            try {
                playwright.close();
            } catch (Exception e) {
                log.error("关闭 Playwright 失败", e);
            }
        }

        log.info("Playwright 浏览器池已销毁 - 累计创建: {}, 累计销毁: {}",
                createdCount.get(), destroyedCount.get());
    }

    @FunctionalInterface
    public interface ContextAction<T> {
        T execute(BrowserContext context) throws Exception;
    }

    /**
     * 池状态信息
     */
    public record PoolStats(
            int active,
            int idle,
            int waiters,
            int created,
            int destroyed,
            long borrowed,
            long returned
    ) {
    }
}