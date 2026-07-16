package top.iencand.translex.client.translate.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * 翻译缓存统一 entry：{@code format + template + registryHash}。
 *
 * <p>两套协议完全隔离，通过 {@code format} 字段区分：
 * <pre>
 *   新格式：{"format":"TSP","template":"[[0||译]]","registryHash":"a83f92..."}
 *   新格式：{"format":"SN","template":"<s0>译</s0>"}
 *   旧 sN：  {"v":"<s0>译</s0>","s":{snapshots}}   ← 向后兼容，parse 当 SN
 *   纯文本： "<s0>译</s0>"                         ← 更早版本，parse 当 SN
 * </pre>
 *
 * <p>统计命中率可按 format 分开看（sN vs TSP）。切换协议时旧 format 的 entry
 * 仍能命中（按各自 format 解码），不串色。</p>
 */
public record TranslationCacheEntry(String format, String template, String registryHash) {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public TranslationCacheEntry {
        if (format == null || format.isEmpty()) format = "SN";
    }

    /** 序列化为缓存 JSON。 */
    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("format", format);
        o.addProperty("template", template);
        if (registryHash != null && !registryHash.isEmpty()) {
            o.addProperty("registryHash", registryHash);
        }
        return GSON.toJson(o);
    }

    /**
     * 解析缓存 JSON，兼容三种格式：
     * <ol>
     *   <li>新格式 {@code {"format":"TSP","template":...,"registryHash":...}}</li>
     *   <li>旧 sN {@code {"v":"<sN>","s":{snapshots}}} -> SN，template=v，无 registryHash</li>
     *   <li>纯文本（早期版本）-> SN，template=原文</li>
     * </ol>
     *
     * @return TranslationCacheEntry，或 null（输入空/解析失败）
     */
    public static TranslationCacheEntry parse(String json) {
        if (json == null || json.isBlank()) return null;
        String trimmed = json.trim();
        // 纯文本（非 JSON）
        if (!trimmed.startsWith("{")) {
            return new TranslationCacheEntry("SN", json, null);
        }
        try {
            JsonObject o = GSON.fromJson(trimmed, JsonObject.class);
            if (o == null) return null;
            if (o.has("format")) {
                String fmt = o.get("format").getAsString();
                String tmpl = o.has("template") ? o.get("template").getAsString() : "";
                String regHash = o.has("registryHash") ? o.get("registryHash").getAsString() : null;
                return new TranslationCacheEntry(fmt, tmpl, regHash);
            }
            // 旧 sN 格式：{"v":"<sN>","s":{snapshots}}
            if (o.has("v")) {
                return new TranslationCacheEntry("SN", o.get("v").getAsString(), null);
            }
        } catch (Exception ignored) {}
        return new TranslationCacheEntry("SN", json, null);
    }
}
