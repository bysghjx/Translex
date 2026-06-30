package top.iencand.translex.client.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import top.iencand.translex.client.spam.SpamFilterData;
import top.iencand.translex.client.spam.SpamHider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.translate.provider.AiProvider;
import top.iencand.translex.client.translate.provider.AiProviders;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 基于 JDK 内置 {@link HttpServer} 的轻量 Web 控制台（零外部依赖）。
 *
 * <h3>双模静态资源挂载</h3>
 * <ol>
 *   <li>开发模式：{@code config/translex/dev.json} 中 isDevelopment=true，
 *       静态文件直接读取本地文件系统，支持浏览器 F5 热刷新。</li>
 *   <li>生产模式：通过 {@code Class.getResourceAsStream()} 读取 Jar 包内置
 *       {@code /assets/translex/web/} 下的文件。</li>
 * </ol>
 *
 * <h3>API 路由</h3>
 * <ul>
 *   <li>GET  /api/config       — ModConfig JSON（字段映射：modelName→model, translationPrompt→systemPrompt）</li>
 *   <li>POST /api/config/save  — JSON → ModConfig → TOML持久化 → 热重载</li>
 *   <li>GET  /api/metrics      — 可观测性指标 JSON</li>
 *   <li>GET  /api/traces       — 网络抓包记录</li>
 *   <li>SSE  /api/debug/console— 长连接日志流</li>
 * </ul>
 */
public class WebServer {

    private static final Logger LOGGER = LoggerFactory.getLogger("TranslexWeb");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final int DEFAULT_PORT = 25587;
    private static final String CLASSPATH_WEB = "/assets/translex/web";

    private static volatile int actualPort = DEFAULT_PORT;
    private static volatile String securityToken;

    /** 供外部（如 /translex config 命令）获取实际监听端口 */
    public static int getPort() { return actualPort; }
    /** 供外部（如 /translex config 命令）获取安全 Token，防止其他本地应用访问 API */
    public static String getToken() { return securityToken; }

    private HttpServer server;
    private ScheduledExecutorService metricsSaver;
    private Path metricsFile;

    public void start() {
        int port = resolvePort();
        boolean isDev = resolveDevMode();
        String webRoot = isDev ? resolveDevWebRoot() : null;

        // 指标持久化路径
        metricsFile = FabricLoader.getInstance().getConfigDir()
                .resolve("translex").resolve("metrics.json");

        // 从磁盘恢复上次运行的指标
        MetricsCollector.get().loadFromFile(metricsFile);

        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);

            // 注册路由
            server.createContext("/api/config",        this::handleGetConfig);
            server.createContext("/api/config/save",   this::handleSaveConfig);
            server.createContext("/api/metrics",       this::handleGetMetrics);
            server.createContext("/api/traces",        this::handleGetTraces);
            server.createContext("/api/debug/console", this::handleSseConsole);
            // 兜底 — 静态文件服务
            server.createContext("/api/spam-filters",      this::handleGetSpamFilters);
            server.createContext("/api/spam-filters/save", this::handleSaveSpamFilters);
            server.createContext("/", ex -> handleStatic(ex, isDev, webRoot));

            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "Translex-Http");
                t.setDaemon(true);
                return t;
            }));
            server.start();

            // 仅启动成功后才赋值，避免 /translex config 打开死链
            actualPort = port;
            securityToken = generateToken();

            // 每 5 分钟自动落盘
            metricsSaver = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Translex-MetricsSaver");
                t.setDaemon(true);
                return t;
            });
            metricsSaver.scheduleAtFixedRate(
                    () -> MetricsCollector.get().saveToFile(metricsFile),
                    5, 5, TimeUnit.MINUTES);

            LOGGER.info("[Web] {} mode — Dashboard at http://localhost:{}",
                    isDev ? "DEV" : "PROD", port);
            LOGGER.info("[Web] Security token: {}", securityToken);
        } catch (IOException e) {
            LOGGER.error("[Web] Failed to start HTTP server", e);
        }
    }

    public void stop() {
        // 停掉定时保存，立刻落盘一次
        if (metricsSaver != null) {
            metricsSaver.shutdown();
            try { metricsSaver.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }
        if (metricsFile != null) {
            MetricsCollector.get().saveToFile(metricsFile);
        }
        if (server != null) {
            server.stop(1);
            LOGGER.info("[Web] Server stopped.");
        }
    }

    // ================================================================
    // 路由处理器
    // ================================================================

    /** GET /api/config */
    private void handleGetConfig(HttpExchange ex) throws IOException {
        if (!checkToken(ex)) { sendForbidden(ex); return; }
        ModConfig cfg = ModConfig.get();
        JsonObject json = new JsonObject();
        json.addProperty("apiKey",                cfg.apiKey);
        json.addProperty("apiUrl",                cfg.apiUrl);
        json.addProperty("model",                 cfg.modelName);
        json.addProperty("provider",              cfg.provider);
        json.addProperty("maxTokens",             cfg.maxTokens);
        json.addProperty("anthropicVersion",      cfg.anthropicVersion);
        json.addProperty("activePreset",          cfg.activePreset);
        json.add("presets",                       GSON.toJsonTree(cfg.presets));
        json.add("availableProviders",            buildProvidersJson());
        json.addProperty("targetLanguage",        cfg.targetLanguage);
        json.addProperty("userChatPrompt",        cfg.userChatPrompt);
        json.addProperty("userItemPrompt",        cfg.userItemPrompt);
        json.addProperty("properNounMode",        cfg.properNounMode);
        json.addProperty("translationMode", cfg.translationMode);
        json.addProperty("buttonStyle",           cfg.buttonStyle);
        json.addProperty("enableTranslateButton",  cfg.enableTranslateButton);
        json.addProperty("outputMode",            cfg.outputMode);
        json.addProperty("enableCachePersistence",cfg.enableCachePersistence);
        json.addProperty("enablePeriodicSave",    cfg.enablePeriodicSave);
        json.addProperty("periodicSaveInterval",  cfg.periodicSaveInterval);
        json.addProperty("cacheMaxEntries",       cfg.cacheMaxEntries);
        json.addProperty("enableChatCompact",     cfg.enableChatCompact);
        json.addProperty("compactTimeSeconds",    cfg.compactTimeSeconds);
        json.addProperty("compactColorCode",      cfg.compactColorCode);
        json.addProperty("debug",               cfg.debug);
        sendJson(ex, 200, json);
    }

    /** POST /api/config/save */
    private void handleSaveConfig(HttpExchange ex) throws IOException {
        if (!checkToken(ex)) { sendForbidden(ex); return; }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, errorJson("Method not allowed"));
            return;
        }
        try {
            String body = readBody(ex);
            JsonObject input = JsonParser.parseString(body).getAsJsonObject();
            ModConfig cfg = ModConfig.get();
            boolean changed = false;

            if (input.has("apiKey"))       { cfg.apiKey = input.get("apiKey").getAsString(); changed = true; }
            if (input.has("apiUrl"))       { cfg.apiUrl = input.get("apiUrl").getAsString(); changed = true; }
            if (input.has("model"))        { cfg.modelName = input.get("model").getAsString(); changed = true; }
            if (input.has("provider"))     { cfg.provider = input.get("provider").getAsString(); changed = true; }
            if (input.has("maxTokens"))    { cfg.maxTokens = input.get("maxTokens").getAsInt(); changed = true; }
            if (input.has("anthropicVersion")) { cfg.anthropicVersion = input.get("anthropicVersion").getAsString(); changed = true; }
            if (input.has("activePreset")) { cfg.activePreset = input.get("activePreset").getAsString(); changed = true; }
            if (input.has("presets") && input.get("presets").isJsonArray()) {
                cfg.presets = parsePresets(input.getAsJsonArray("presets"));
                changed = true;
            }
            if (input.has("targetLanguage")) {
                String tl = input.get("targetLanguage").getAsString();
                cfg.targetLanguage = (tl == null || tl.isBlank())
                        ? top.iencand.translex.client.translate.TranslationPrompts.DEFAULT_TARGET_LANGUAGE
                        : tl;
                changed = true;
            }
            if (input.has("userChatPrompt")) { cfg.userChatPrompt = input.get("userChatPrompt").getAsString(); changed = true; }
            if (input.has("userItemPrompt")) { cfg.userItemPrompt = input.get("userItemPrompt").getAsString(); changed = true; }
            if (input.has("properNounMode")) { cfg.properNounMode = input.get("properNounMode").getAsString(); changed = true; }
            if (input.has("translationMode"))    { cfg.translationMode = input.get("translationMode").getAsString(); changed = true; }
            if (input.has("buttonStyle"))   { cfg.buttonStyle = input.get("buttonStyle").getAsString(); changed = true; }
            if (input.has("enableTranslateButton")) { cfg.enableTranslateButton = input.get("enableTranslateButton").getAsBoolean(); changed = true; }
            if (input.has("outputMode"))    { cfg.outputMode = input.get("outputMode").getAsString(); changed = true; }
            if (input.has("enableCachePersistence")) { cfg.enableCachePersistence = input.get("enableCachePersistence").getAsBoolean(); changed = true; }
            if (input.has("enablePeriodicSave"))     { cfg.enablePeriodicSave = input.get("enablePeriodicSave").getAsBoolean(); changed = true; }
            if (input.has("periodicSaveInterval"))   { cfg.periodicSaveInterval = input.get("periodicSaveInterval").getAsInt(); changed = true; }
            if (input.has("cacheMaxEntries"))        { cfg.cacheMaxEntries = input.get("cacheMaxEntries").getAsInt(); changed = true; }
            if (input.has("enableChatCompact"))      { cfg.enableChatCompact = input.get("enableChatCompact").getAsBoolean(); changed = true; }
            if (input.has("compactTimeSeconds"))     { cfg.compactTimeSeconds = input.get("compactTimeSeconds").getAsInt(); changed = true; }
            if (input.has("compactColorCode"))       { cfg.compactColorCode = input.get("compactColorCode").getAsString(); changed = true; }
            if (input.has("debug"))               { cfg.debug = input.get("debug").getAsBoolean(); changed = true; }

            if (changed) {
                ModConfig.forceSave();
                ModConfig.reload();
                // reload() 内部已 broadcast "Config reloaded from disk"，不再重复
                JsonObject result = new JsonObject();
                result.addProperty("success", true);
                result.addProperty("message", "Config saved & reloaded");
                sendJson(ex, 200, result);
            } else {
                sendJson(ex, 400, errorJson("No fields changed"));
            }
        } catch (Exception e) {
            LOGGER.error("[Web] Failed to save config", e);
            sendJson(ex, 500, errorJson(e.getMessage()));
        }
    }

    /** 构造可用供应商列表 JSON：[{id, name}] */
    private static JsonArray buildProvidersJson() {
        JsonArray arr = new JsonArray();
        for (AiProvider p : AiProviders.all().values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", p.id());
            o.addProperty("name", p.displayName());
            arr.add(o);
        }
        return arr;
    }

    /** 把前端传来的预设数组解析为 ModConfig.Preset 列表。 */
    private static List<ModConfig.Preset> parsePresets(JsonArray arr) {
        List<ModConfig.Preset> list = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            ModConfig.Preset p = new ModConfig.Preset();
            p.name = optString(o, "name", "");
            if (p.name.isBlank()) continue;
            p.provider = optString(o, "provider", "openai");
            p.apiUrl = optString(o, "apiUrl", "");
            p.apiKey = optString(o, "apiKey", "");
            p.model = optString(o, "model", "");
            p.maxTokens = o.has("maxTokens") && !o.get("maxTokens").isJsonNull()
                    ? o.get("maxTokens").getAsInt() : 4096;
            p.anthropicVersion = optString(o, "anthropicVersion", "2023-06-01");
            list.add(p);
        }
        return list;
    }

    private static String optString(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    /** GET /api/metrics */
    private void handleGetMetrics(HttpExchange ex) throws IOException {
        if (!checkToken(ex)) { sendForbidden(ex); return; }
        MetricsCollector mc = MetricsCollector.get();
        JsonObject json = new JsonObject();
        json.addProperty("localHits",            mc.getLocalHits());
        json.addProperty("aiRequests",           mc.getAiRequests());
        json.addProperty("totalEstimatedTokens",     mc.getTotalEstimatedTokens());
        json.addProperty("totalSavedTokens",         mc.getTotalSavedTokens());
        json.addProperty("totalActualPromptTokens",  mc.getTotalActualPromptTokens());
        json.addProperty("totalActualCompletionTokens", mc.getTotalActualCompletionTokens());
        json.addProperty("totalActualTokens",        mc.getTotalActualTokens());
        json.addProperty("hasActualTokenData",      mc.hasActualTokenData());
        json.add("latencyHistory",               GSON.toJsonTree(mc.getLatencyHistory()));
        json.addProperty("sseClientCount",       ConsoleBroadcaster.getClientCount());
        sendJson(ex, 200, json);
    }

    /** GET /api/traces */
    private void handleGetTraces(HttpExchange ex) throws IOException {
        if (!checkToken(ex)) { sendForbidden(ex); return; }
        List<MetricsCollector.TraceEntry> traces = MetricsCollector.get().getTraces();
        // 手动构建 JSON 数组以匹配前端期望的字段名（驼峰）
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < traces.size(); i++) {
            if (i > 0) sb.append(",");
            MetricsCollector.TraceEntry t = traces.get(i);
            sb.append("{\"id\":").append(t.id)
              .append(",\"timestamp\":\"").append(escape(t.timestamp))
              .append("\",\"method\":\"").append(escape(t.method))
              .append("\",\"url\":\"").append(escape(t.url))
              .append("\",\"success\":").append(t.success)
              .append(",\"durationMs\":").append(t.durationMs)
              .append(",\"requestBody\":").append(GSON.toJson(t.requestBody))
              .append(",\"responseBody\":").append(GSON.toJson(t.responseBody))
              .append(",\"estimatedTokens\":").append(t.estimatedTokens)
              .append(",\"estimatedSystemPromptTokens\":").append(t.estimatedSystemPromptTokens)
              .append(",\"estimatedPayloadTokens\":").append(t.estimatedPayloadTokens)
              .append(",\"promptTokens\":").append(t.promptTokens)
              .append(",\"completionTokens\":").append(t.completionTokens)
              .append(",\"totalTokens\":").append(t.totalTokens)
              .append(",\"cachedTokens\":").append(t.cachedTokens)
              .append(",\"reasoningTokens\":").append(t.reasoningTokens)
              .append(",\"hasTokenData\":").append(t.hasTokenData)
              .append("}");
        }
        sb.append("]");
        sendString(ex, 200, "application/json", sb.toString());
    }

    /** SSE GET /api/debug/console */
    private void handleSseConsole(HttpExchange ex) throws IOException {
        if (!checkToken(ex)) { sendForbidden(ex); return; }
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0); // chunked encoding

        OutputStream out = ex.getResponseBody();
        ConsoleBroadcaster.addClient(out);

        try {
            // 保持连接：每 30 秒发心跳注释，循环直到客户端断开
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(30000);
                    out.write(":\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (IOException e) {
            // 客户端断开连接
        } finally {
            ConsoleBroadcaster.removeClient(out);
            try { out.close(); } catch (IOException ignored) {}
        }
    }

    // ================================================================
    // SpamHider 过滤器 API
    // ================================================================

    /** GET /api/spam-filters — 返回 SpamHider 状态和过滤器列表 */
    private void handleGetSpamFilters(HttpExchange ex) throws IOException {
        if (!checkToken(ex)) { sendForbidden(ex); return; }
        SpamHider sh = SpamHider.getInstance();
        JsonObject json = new JsonObject();
        json.addProperty("enabled", sh.isEnabled());
        json.add("filters", GSON.toJsonTree(sh.getFilters()));
        sendJson(ex, 200, json);
    }

    /** POST /api/spam-filters/save — 保存 SpamHider 状态和全部过滤器 */
    private void handleSaveSpamFilters(HttpExchange ex) throws IOException {
        if (!checkToken(ex)) { sendForbidden(ex); return; }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, errorJson("Method not allowed"));
            return;
        }
        try {
            String body = readBody(ex);
            JsonObject input = JsonParser.parseString(body).getAsJsonObject();
            SpamHider sh = SpamHider.getInstance();

            if (input.has("enabled")) {
                sh.setEnabled(input.get("enabled").getAsBoolean());
            }

            if (input.has("filters")) {
                java.lang.reflect.Type filterListType =
                        new TypeToken<java.util.List<SpamFilterData.Filter>>() {}.getType();
                java.util.List<SpamFilterData.Filter> loaded =
                        GSON.fromJson(input.getAsJsonArray("filters"), filterListType);
                if (loaded != null) {
                    for (SpamFilterData.Filter f : loaded) {
                        f.recompile();
                    }
                    sh.replaceAll(loaded);
                }
            }

            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            result.addProperty("message", "Spam filters saved (" + sh.getFilters().size() + " rules)");
            sendJson(ex, 200, result);
        } catch (Exception e) {
            LOGGER.error("[Web] Failed to save spam filters", e);
            sendJson(ex, 500, errorJson(e.getMessage()));
        }
    }

    // ================================================================
    // 静态文件服务
    // ================================================================

    private void handleStatic(HttpExchange ex, boolean isDev, String webRoot) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/") || path.isEmpty()) path = "/index.html";

        // API 路径不打静态文件
        if (path.startsWith("/api/")) {
            sendJson(ex, 404, errorJson("Not found"));
            return;
        }

        // 安全检查：防止路径穿越
        if (path.contains("..")) {
            sendString(ex, 403, "text/plain", "Forbidden");
            return;
        }

        byte[] content;
        String contentType = guessContentType(path);

        if (isDev && webRoot != null) {
            // 开发模式：读本地文件系统
            Path file = Path.of(webRoot, path);
            if (Files.exists(file) && !Files.isDirectory(file)) {
                content = Files.readAllBytes(file);
            } else {
                sendString(ex, 404, "text/plain", "Not found");
                return;
            }
        } else {
            // 生产模式：读 classpath（Jar 内置）
            String classpathResource = CLASSPATH_WEB + path;
            InputStream is = getClass().getResourceAsStream(classpathResource);
            if (is == null) {
                sendString(ex, 404, "text/plain", "Not found");
                return;
            }
            content = is.readAllBytes();
            is.close();
        }

        ex.getResponseHeaders().set("Content-Type", contentType);
        if (!isDev) {
            ex.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
        }
        ex.sendResponseHeaders(200, content.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(content);
        }
    }

    // ================================================================
    // 工具方法
    // ================================================================

    /**
     * 校验请求中的 token 查询参数是否与服务端生成的一致。
     * 静态文件不校验 —— 仅 /api/* 路由需要。
     */
    private boolean checkToken(HttpExchange ex) {
        String query = ex.getRequestURI().getQuery();
        if (query == null) return false;
        for (String param : query.split("&")) {
            int eq = param.indexOf('=');
            if (eq > 0 && param.substring(0, eq).equals("token")
                     && param.substring(eq + 1).equals(securityToken)) {
                return true;
            }
        }
        return false;
    }

    private void sendForbidden(HttpExchange ex) throws IOException {
        sendJson(ex, 403, errorJson("Forbidden: invalid or missing token"));
    }

    /** 生成 128-bit 随机安全 Token（32 位十六进制字符串） */
    private static String generateToken() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void sendJson(HttpExchange ex, int code, JsonObject json) throws IOException {
        byte[] bytes = GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendString(HttpExchange ex, int code, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private JsonObject errorJson(String msg) {
        JsonObject err = new JsonObject();
        err.addProperty("success", false);
        err.addProperty("error", msg);
        return err;
    }

    private String guessContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css"))  return "text/css; charset=utf-8";
        if (path.endsWith(".js"))   return "application/javascript; charset=utf-8";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        if (path.endsWith(".png"))  return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".gif"))  return "image/gif";
        if (path.endsWith(".webp")) return "image/webp";
        if (path.endsWith(".svg"))  return "image/svg+xml";
        if (path.endsWith(".ico"))  return "image/x-icon";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".woff"))  return "font/woff";
        return "application/octet-stream";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ================================================================
    // 开发模式判定
    // ================================================================

    private boolean resolveDevMode() {
        Path devFile = getDevJsonPath();
        if (Files.exists(devFile)) {
            try (Reader reader = Files.newBufferedReader(devFile, StandardCharsets.UTF_8)) {
                JsonObject dev = JsonParser.parseReader(reader).getAsJsonObject();
                return dev.has("isDevelopment") && dev.get("isDevelopment").getAsBoolean();
            } catch (Exception e) {
                LOGGER.warn("[Web] Failed to parse dev.json", e);
            }
        }
        return false;
    }

    private int resolvePort() {
        Path devFile = getDevJsonPath();
        if (Files.exists(devFile)) {
            try (Reader reader = Files.newBufferedReader(devFile, StandardCharsets.UTF_8)) {
                JsonObject dev = JsonParser.parseReader(reader).getAsJsonObject();
                if (dev.has("port")) return dev.get("port").getAsInt();
            } catch (Exception ignored) {}
        }
        return DEFAULT_PORT;
    }

    private String resolveDevWebRoot() {
        Path devFile = getDevJsonPath();
        if (Files.exists(devFile)) {
            try (Reader reader = Files.newBufferedReader(devFile, StandardCharsets.UTF_8)) {
                JsonObject dev = JsonParser.parseReader(reader).getAsJsonObject();
                if (dev.has("webRoot")) return dev.get("webRoot").getAsString();
            } catch (Exception ignored) {}
        }
        return FabricLoader.getInstance().getGameDir()
                .resolve("../src/main/resources/assets/translex/web")
                .normalize().toAbsolutePath().toString();
    }

    private Path getDevJsonPath() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("translex").resolve("dev.json");
    }
}
