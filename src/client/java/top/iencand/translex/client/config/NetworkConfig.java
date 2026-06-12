package top.iencand.translex.client.config;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 网络重试的共享线程池配置。
 * 核心线程 2，最大线程 4，有界队列 200，溢出时由调用线程处理（CallerRunsPolicy）。
 */
public final class NetworkConfig {

    private NetworkConfig() {}

    public static final ThreadPoolExecutor RETRY_EXECUTOR = new ThreadPoolExecutor(
            2, 4,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(200),
            r -> {
                Thread t = new Thread(r, "Translex-Retry");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    /** 优雅关闭线程池，最多等待 2 秒 */
    public static void shutdown() {
        RETRY_EXECUTOR.shutdown();
        try {
            if (!RETRY_EXECUTOR.awaitTermination(2, TimeUnit.SECONDS)) {
                RETRY_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            RETRY_EXECUTOR.shutdownNow();
        }
    }
}
