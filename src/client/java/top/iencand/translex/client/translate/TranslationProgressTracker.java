package top.iencand.translex.client.translate;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import top.iencand.translex.client.ext.IChatHudExt;
import top.iencand.translex.client.util.I18nHelper;

import java.util.List;

/**
 * Manages "loading…" progress indicators in the chat HUD.
 * Supports showing, updating (remove + re-add), and removing messages
 * identified by a hidden marker tag.
 */
public class TranslationProgressTracker {
    private static final String LOADING_MARKER_PREFIX = "​§8[TL_WAIT_";
    private static final String LOADING_MARKER_SUFFIX = "]​";

    /** Show a new loading message. */
    public void showLoading(String displayId) {
        MinecraftClient.getInstance().execute(() -> {
            if (MinecraftClient.getInstance().inGameHud == null) return;

            IChatHudExt chatHud = (IChatHudExt) MinecraftClient.getInstance().inGameHud.getChatHud();

            MutableText prefix = Text.literal(I18nHelper.translate("translex.prefix.name")).formatted(Formatting.GREEN)
                    .append(Text.literal(I18nHelper.translate("translex.prefix.separator")).formatted(Formatting.BLUE));

            MutableText status = Text.literal(I18nHelper.translate("translex.info.processing"))
                    .append(Text.literal("... "))
                    .append(Text.literal(LOADING_MARKER_PREFIX + displayId + LOADING_MARKER_SUFFIX).formatted(Formatting.DARK_GRAY))
                    .formatted(Formatting.YELLOW);

            chatHud.translex$forceAddMessage(prefix.append(status));
        });
    }

    /**
     * Update an existing loading message in-place.
     * Removes the old marker and adds a new message with the updated text.
     */
    public void updateLoading(String displayId, String newText) {
        MinecraftClient.getInstance().execute(() -> {
            if (MinecraftClient.getInstance().inGameHud == null) return;

            IChatHudExt chatHud = (IChatHudExt) MinecraftClient.getInstance().inGameHud.getChatHud();
            List<ChatHudLine> messages = chatHud.translex$getMessages();

            String targetTag = LOADING_MARKER_PREFIX + displayId + "]";

            // Remove old loading message
            messages.removeIf(line -> line.content().getString().contains(targetTag));

            // Add updated message
            MutableText prefix = Text.literal(I18nHelper.translate("translex.prefix.name")).formatted(Formatting.GREEN)
                    .append(Text.literal(I18nHelper.translate("translex.prefix.separator")).formatted(Formatting.BLUE));

            MutableText status = Text.literal(newText)
                    .append(Text.literal(" "))
                    .append(Text.literal(LOADING_MARKER_PREFIX + displayId + LOADING_MARKER_SUFFIX).formatted(Formatting.DARK_GRAY))
                    .formatted(Formatting.YELLOW);

            chatHud.translex$forceAddMessage(prefix.append(status));
            chatHud.translex$refreshMessages();
        });
    }

    /** Remove a loading message by its display ID. */
    public void removeLoading(String displayId) {
        MinecraftClient.getInstance().execute(() -> {
            if (MinecraftClient.getInstance().inGameHud == null) return;

            IChatHudExt chatHud = (IChatHudExt) MinecraftClient.getInstance().inGameHud.getChatHud();
            List<ChatHudLine> messages = chatHud.translex$getMessages();

            String targetTag = LOADING_MARKER_PREFIX + displayId + "]";

            boolean removed = messages.removeIf(line ->
                    line.content().getString().contains(targetTag));

            if (removed) {
                chatHud.translex$refreshMessages();
            }
        });
    }
}
