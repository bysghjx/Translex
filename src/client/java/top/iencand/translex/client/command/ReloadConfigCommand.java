package top.iencand.translex.client.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.Translate.TranslationManager;
import top.iencand.translex.client.util.I18nHelper; // 导入工具类

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class ReloadConfigCommand {

    private static final String COMMAND_NAME = "translexreload";
    private final TranslationManager translationManager;

    public ReloadConfigCommand(TranslationManager translationManager) {
        this.translationManager = translationManager;
    }

    public void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, environment) -> {
            dispatcher.register(literal(COMMAND_NAME)
                    .executes(this::executeCommand));
        });
    }

    private int executeCommand(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();

        try {
            // 1. 重新加载配置文件 (这一步最关键，它更新了 ModConfig 的单例)
            ModConfig.loadConfig();
            ModConfig config = ModConfig.get();

            // 2. 原有的 translationManager.updateSettings 调用已经不需要了，直接删除即可

            // 3. 发送成功重载提示
            source.sendFeedback(Text.literal(I18nHelper.getPrefixed("translex.info.config_reloaded")));

            // 4. 发送模式信息提示
            String modeKey = config.enableMessageIdSystem ?
                    "translex.info.mode_message_id" : "translex.info.mode_command_text";

            source.sendFeedback(Text.literal(I18nHelper.getPrefixed(modeKey)));

        } catch (Exception e) {
            source.sendError(Text.literal(I18nHelper.getPrefixed("translex.error.config_load")));
            e.printStackTrace();
            return 0;
        }

        return Command.SINGLE_SUCCESS;
    }
}