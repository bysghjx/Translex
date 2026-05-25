package top.iencand.translex.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.iencand.translex.client.Translate.TranslationManager;
import top.iencand.translex.client.command.TranslexCommand;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.keybinding.ModKeybindings;
import top.iencand.translex.client.listener.ChatTranslateHandler;
import top.iencand.translex.client.listener.ClientStateManager;
import top.iencand.translex.client.listener.LegacyChatHandler;

public class TranslexClient implements ClientModInitializer {

    public static final String MOD_ID = "translex";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private TranslationManager translationManager;
    private ChatTranslateHandler chatTranslateHandler;
    private LegacyChatHandler legacyChatHandler;
    private ClientStateManager clientStateManager;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[{}] Initializing client components...", MOD_ID);

        // 1. 初始化配置（get() 自动懒加载）
        ModConfig config = ModConfig.get();

        // 2. 实例化核心指挥官 TranslationManager
        translationManager = new TranslationManager();

        // 3. 初始化持久化层
        if (config.enableCachePersistence) {
            translationManager.initializePersistence(ModConfig.getCacheFile());
            LOGGER.info("[{}] Cache persistence enabled and initialized.", MOD_ID);
        } else {
            LOGGER.warn("[{}] Cache persistence is disabled in config.", MOD_ID);
        }

        // 4. 根据配置决定 chatTranslateHandler 是否可用
        if (config.enableMessageIdSystem) {
            LOGGER.info("[{}] Enabling Message ID system.", MOD_ID);
            chatTranslateHandler = new ChatTranslateHandler();
            legacyChatHandler = null;
        } else {
            LOGGER.info("[{}] Enabling Legacy Command+Text system.", MOD_ID);
            chatTranslateHandler = null;
            legacyChatHandler = new LegacyChatHandler();
            legacyChatHandler.registerEvents();
        }

        // 5. 注册统一的 /translex 子命令系统
        new TranslexCommand(translationManager, chatTranslateHandler).register();

        // 6. 注册全局事件与按键绑定
        ModKeybindings.register();

        clientStateManager = new ClientStateManager(translationManager);
        clientStateManager.registerEvents();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("[{}] Shutting down, saving translation data...", MOD_ID);
            if (translationManager != null) {
                translationManager.shutdown();
            }
        }));
    }

    public TranslationManager getTranslationManager() {
        return translationManager;
    }

    public ChatTranslateHandler getChatTranslateHandler() {
        return chatTranslateHandler;
    }
}
