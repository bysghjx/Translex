package top.iencand.translex.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.iencand.translex.client.translate.TranslationManager;
import top.iencand.translex.client.command.TranslexCommand;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.keybinding.ModKeybindings;
import top.iencand.translex.client.listener.AutoChatHandler;
import top.iencand.translex.client.listener.ChatTranslateHandler;
import top.iencand.translex.client.listener.ClientStateManager;
import top.iencand.translex.client.listener.LegacyChatHandler;
import top.iencand.translex.client.listener.MessageLookup;
import top.iencand.translex.client.config.NetworkConfig;
import top.iencand.translex.client.spam.SpamOverlayRenderer;
import top.iencand.translex.client.web.WebServer;

/**
 * Translex 模组的客户端初始化入口。
 * 负责初始化翻译管理器、配置、按键绑定、事件监听器和 Web 控制台。
 */
public class TranslexClient implements ClientModInitializer {

    public static final String MOD_ID = "translex";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private TranslationManager translationManager;
    private ChatTranslateHandler chatTranslateHandler;
    private LegacyChatHandler legacyChatHandler;
    private AutoChatHandler autoChatHandler;
    private ClientStateManager clientStateManager;
    private WebServer webServer;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[{}] 正在初始化客户端组件...", MOD_ID);

        // 0. 检测是否为首次启动（config.toml 和旧版 config.json 均不存在）
        java.io.File configDir = new java.io.File(
                net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().toFile(), "translex");
        boolean isFirstLaunch = !new java.io.File(configDir, "config.toml").exists()
                && !new java.io.File(configDir, "config.json").exists();

        // 1. 初始化配置（get() 自动懒加载）
        ModConfig config = ModConfig.get();

        // 2. 实例化核心翻译管理器
        translationManager = new TranslationManager();

        // 3. 初始化缓存持久化层（debug 模式下跳过缓存以方便观察原始 API 调用）
        if (config.enableCachePersistence && !config.debug) {
            translationManager.initializePersistence(ModConfig.getCacheFile());
            LOGGER.info("[{}] 缓存持久化已启用并初始化。", MOD_ID);
        } else if (config.debug) {
            LOGGER.info("[{}] 缓存已禁用（调试模式）。", MOD_ID);
        } else {
            LOGGER.warn("[{}] 配置中缓存持久化功能已禁用。", MOD_ID);
        }

        // 4. 根据 translationMode 选择聊天翻译模式
        MessageLookup messageLookup;
        switch (config.translationMode) {
            case "message_id" -> {
                LOGGER.info("[{}] 翻译模式：message_id（消息ID模式）", MOD_ID);
                chatTranslateHandler = new ChatTranslateHandler();
                messageLookup = chatTranslateHandler;
                legacyChatHandler = null;
                autoChatHandler = null;
            }
            case "text" -> {
                LOGGER.info("[{}] 翻译模式：text（纯文本模式）", MOD_ID);
                chatTranslateHandler = null;
                legacyChatHandler = new LegacyChatHandler();
                legacyChatHandler.registerEvents();
                messageLookup = null;
                autoChatHandler = null;
            }
            default -> { // "auto" 或未知
                LOGGER.info("[{}] 翻译模式：auto（自动模式）", MOD_ID);
                autoChatHandler = new AutoChatHandler();
                messageLookup = autoChatHandler;
                chatTranslateHandler = null;
                legacyChatHandler = null;
            }
        }

        // 5. 注册统一的 /translex 子命令系统
        new TranslexCommand(translationManager, messageLookup).register();

        // 6. 注册全局事件与按键绑定
        ModKeybindings.register();

        clientStateManager = new ClientStateManager(translationManager);
        clientStateManager.registerEvents();

        // 7. 初始化 SpamHider 浮动 HUD 渲染器
        SpamOverlayRenderer.getInstance().init();

        // 8. 启动内嵌 Web 控制台（Dashboard）
        webServer = new WebServer();
        webServer.start();

        // 9. Debug 模式：启动时自动打开浏览器到网络抓包页面
        if (config.debug) {
            new Thread(() -> {
                try {
                    Thread.sleep(2000); // 等待 Web 服务器完全启动
                } catch (InterruptedException ignored) {}
                int port = WebServer.getPort();
                String token = WebServer.getToken();
                String url = "http://127.0.0.1:" + port + "/?token=" + token + "#debug";
                net.minecraft.util.Util.getOperatingSystem().open(java.net.URI.create(url));
                LOGGER.info("[{}] 调试模式：已打开调试控制台 {}", MOD_ID, url);
            }, "Translex-DebugOpener").start();
        }

        // 10. 首次启动：自动打开 Web 配置面板，方便用户设置 API Key
        if (isFirstLaunch) {
            new Thread(() -> {
                try {
                    Thread.sleep(2500); // 稍长延迟，确保 Web 服务器与首次配置写入完成
                } catch (InterruptedException ignored) {}
                int port = WebServer.getPort();
                String token = WebServer.getToken();
                String url = "http://127.0.0.1:" + port + "/?token=" + token + "#welcome";
                net.minecraft.util.Util.getOperatingSystem().open(java.net.URI.create(url));
                LOGGER.info("[{}] 首次启动：已自动打开配置面板 {}", MOD_ID, url);
            }, "Translex-FirstLaunchOpener").start();
        }

        // 注册关闭钩子，确保退出时保存翻译数据
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("[{}] 正在关闭，保存翻译数据...", MOD_ID);
            if (webServer != null) {
                webServer.stop();
            }
            if (translationManager != null) {
                translationManager.shutdown();
            }
            NetworkConfig.shutdown();
        }));
    }

    public TranslationManager getTranslationManager() {
        return translationManager;
    }

    public ChatTranslateHandler getChatTranslateHandler() {
        return chatTranslateHandler;
    }
}
