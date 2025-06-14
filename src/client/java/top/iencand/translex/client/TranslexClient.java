package top.iencand.translex.client;

import net.fabricmc.api.ClientModInitializer;
import top.iencand.translex.client.command.ReloadConfigCommand;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.listener.ChatTranslateHandler;
import top.iencand.translex.client.command.LegacyTranslateCommand;
import top.iencand.translex.client.command.TranslateCommand;

import top.iencand.translex.client.Translate.TranslationManager;
import top.iencand.translex.client.keybinding.ModKeybindings;
import top.iencand.translex.client.listener.ClientStateManager;
import top.iencand.translex.client.listener.LegacyChatHandler;


public class TranslexClient implements ClientModInitializer {

    public static final String MOD_ID = "translex";
    private TranslationManager translationManager;
    private ChatTranslateHandler chatTranslateHandler; // 消息 ID 模式的聊天处理器 (内部构造函数会注册事件)
    private LegacyTranslateCommand legacyTranslateCommand; // 命令+文本模式的命令处理器 (register() 方法注册命令)
    private TranslateCommand translateCommand; // 消息 ID 模式的命令处理器 (register() 方法注册命令)
    private ClientStateManager clientStateManager;
    private LegacyChatHandler legacyChatHandlerForLegacyMode;


    @Override
    public void onInitializeClient() {

        // 在客户端初始化时创建组件实例
        translationManager = new TranslationManager();
        // 创建 ChatTranslateHandler 实例，这会自动注册 MODIFY_GAME 事件
        ModConfig.loadConfig();
        ModConfig config = ModConfig.get();

        translationManager.updateSettings(
                config.apiKey,
                config.translationPrompt,
                config.modelName,
                config.apiUrl
        );
        translationManager.setCacheFile(ModConfig.getCacheFile());

        new ReloadConfigCommand(translationManager).register();
        ModKeybindings.register();

        clientStateManager = new ClientStateManager(translationManager); // 实例化
        clientStateManager.registerEvents(); // 调用注册事件的方法


        /**
         * 启用缓存系统
        **/
        if (config.enableCachePersistence) {
            translationManager.loadCache();
        } else {
            System.out.println("[" + MOD_ID + "] Cache persistence is disabled by config, not loading cache.");
        }

        /**
         * 启用消息id系统
         **/

        if (config.enableMessageIdSystem) {
            System.out.println("[" + MOD_ID + "] Enabling Message ID system.");
            // 实例化并注册 ChatTranslateHandler (它内部构造函数会注册 MODIFY_GAME 事件)
            chatTranslateHandler = new ChatTranslateHandler();
            // 实例化并注册消息 ID 模式的命令
            translateCommand = new TranslateCommand(chatTranslateHandler, translationManager);
            translateCommand.register(); // 调用其 register 方法注册命令

            // 确保旧模式的组件为 null
            legacyTranslateCommand = null;

        } else {
            System.out.println("[" + MOD_ID + "] Enabling Legacy Command+Text system.");

            legacyTranslateCommand = new LegacyTranslateCommand(translationManager); // 实例化
            legacyTranslateCommand.register(); // 调用 register 方法注册命令

            // 确保新模式的组件为 null
            chatTranslateHandler = null;
            translateCommand = null;

            // *** 注册 LegacyChatHandler (刚刚迁移完成的部分) ***
            // 在命令+文本模式下，需要 LegacyChatHandler 来在聊天旁添加按钮
            legacyChatHandlerForLegacyMode = new LegacyChatHandler(); // 实例化
            legacyChatHandlerForLegacyMode.registerEvents(); // 调用注册事件的方法


            // 确保新模式的组件为 null
            chatTranslateHandler = null;
            translateCommand = null;

            // TODO: 如果您打算迁移 LegacyChatHandler.java
            // 并希望在命令+文本模式下自动在聊天消息旁添加 /translate 按钮，
            // 您需要在这里实例化并注册迁移后的 LegacyChatHandler。
            // 例如：LegacyChatHandler legacyChatHandlerForLegacyMode = new LegacyChatHandler();
            //       legacyChatHandlerForLegacyMode.register(); // 假设 LegacyChatHandler 也有 register 方法

        }


    }

    // --- 公共 Getter 方法 (如果其他类需要访问这些组件) ---
    public TranslationManager getTranslationManager() {
        return translationManager;
    }

    public Object getTranslateCommandInstance() {
        if (ModConfig.get().enableMessageIdSystem) {
            return translateCommand;
        } else {
            return legacyTranslateCommand;
        }
    }

    public ChatTranslateHandler getChatTranslateHandler() {
        return chatTranslateHandler;
    }

}
