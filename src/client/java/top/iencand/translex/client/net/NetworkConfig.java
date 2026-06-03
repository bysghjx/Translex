package top.iencand.translex.client.net;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Shared thread-pool configuration for network retries.
 * Core 2, max 4, bounded queue of 200, CallerRunsPolicy on overflow.
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
