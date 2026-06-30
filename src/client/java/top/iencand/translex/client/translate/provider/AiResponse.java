package top.iencand.translex.client.translate.provider;

/**
 * 解析后的供应商响应结果。
 *
 * @param content    译文文本内容（成功时非空）
 * @param success    是否成功解析出内容
 * @param prompt     prompt（输入）token 用量，未知为 0
 * @param completion completion（输出）token 用量，未知为 0
 * @param total      总 token 用量，未知则回退为 prompt+completion
 * @param cached     命中缓存的 prompt token，未知为 0
 * @param reasoning  推理 token，未知为 0
 */
public record AiResponse(
        String content,
        boolean success,
        long prompt,
        long completion,
        long total,
        long cached,
        long reasoning) {

    /** 构造一个仅含内容、无 token 统计的成功结果。 */
    public static AiResponse ofContent(String content) {
        return new AiResponse(content, content != null && !content.isEmpty(), 0, 0, 0, 0, 0);
    }

    /** 构造一个失败结果。 */
    public static AiResponse failure() {
        return new AiResponse(null, false, 0, 0, 0, 0, 0);
    }
}
