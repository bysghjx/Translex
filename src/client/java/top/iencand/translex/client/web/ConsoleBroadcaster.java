package top.iencand.translex.client.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 日志广播器，基于 JDK {@link OutputStream}（无外部依赖）。
 *
 * <p>每个 SSE 客户端持有一个 {@link OutputStream} 引用，
 * {@link #broadcast(String, String)} 向所有已连接前端推送 JSON 日志行。</p>
 */
public class ConsoleBroadcaster {

    private static final Logger LOGGER = LoggerFactory.getLogger("TranslexConsole");
    private static final DateTimeFormatter TF = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final Set<OutputStream> clients = ConcurrentHashMap.newKeySet();

    private ConsoleBroadcaster() {}

    public static void addClient(OutputStream out) {
        clients.add(out);
        broadcastRaw("INFO", "Frontend connected (" + clients.size() + " active)");
    }

    public static void removeClient(OutputStream out) {
        clients.remove(out);
        LOGGER.info("[Console] Client disconnected, {} remaining", clients.size());
    }

    public static void broadcast(String level, String message) {
        broadcastRaw(level, message);
    }

    // ---------------------------------------------------------------
    // 内部实现
    // ---------------------------------------------------------------

    private static void broadcastRaw(String level, String message) {
        if (clients.isEmpty()) return;

        String timestamp = LocalTime.now().format(TF);
        byte[] bytes = buildSseBytes(timestamp, level.toUpperCase(), message);

        for (OutputStream client : clients) {
            try {
                client.write(bytes);
                client.flush();
            } catch (IOException e) {
                clients.remove(client);
            }
        }
    }

    /** 构建 SSE 格式字节数组 */
    private static byte[] buildSseBytes(String timestamp, String level, String message) {
        String escapedMsg = message
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        String sse = "data: {\"timestamp\":\"" + timestamp
                + "\",\"level\":\"" + level
                + "\",\"message\":\"" + escapedMsg
                + "\"}\n\n";
        return sse.getBytes(StandardCharsets.UTF_8);
    }

    public static int getClientCount() {
        return clients.size();
    }
}
