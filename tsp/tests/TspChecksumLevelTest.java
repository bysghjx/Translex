package tsp.tests;

import tsp.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TSP v1.1 checksum Level 1/2/3 测试。
 * - Level 0: (ID, HASH) 校验通过
 * - Level 2: HASH 唯一匹配 -> 确定性修复 ID
 * - Level 3a: HASH 乱编 -> invalid
 * - Level 3b: HASH 多匹配（同内容不同色）-> ambiguous，不猜
 * - v1.0 兼容: 无 checksum 不校验
 * Run: java tsp.tests.TspChecksumLevelTest
 */
public final class TspChecksumLevelTest {

    private static int passed = 0, failed = 0;
    private static final Style GRAY = Style.of("#AAAAAA");
    private static final Style GREEN = Style.of("#55FF55");
    private static final Style AQUA = Style.of("#55FFFF");

    public static void main(String[] args) {
        System.out.println("=== TSP Checksum Level 1/2/3 Tests ===\n");
        testRoundTrip();
        testLevel2Repair();
        testLevel3Invalid();
        testLevel3Ambiguous();
        testV1CompatNoChecksum();
        System.out.println("\n---");
        System.out.println("Passed: " + passed + "  Failed: " + failed);
        if (failed > 0) { System.out.println("SOME TESTS FAILED!"); System.exit(1); }
        System.out.println("All checksum level tests passed.");
    }

    /** 用 segs 建 decReg + idHashSet + hashToIds（跟 encode 一致）。 */
    private static TspRegistry buildForDecode(List<StyledSegment> segs,
                                               Set<String> idHashSet,
                                               Map<String, List<Integer>> hashToIds) {
        TspRegistry reg = new TspRegistry();
        for (StyledSegment seg : segs) {
            if (!seg.isPlain()) {
                int id = reg.register(seg.style());
                String hash = TspEncoder.sha4(seg.text());
                idHashSet.add(id + ":" + hash);
                hashToIds.computeIfAbsent(hash, k -> new ArrayList<>()).add(id);
            }
        }
        return reg;
    }

    // Level 0: round-trip 正确
    private static void testRoundTrip() {
        System.out.print("  Level 0 round-trip ... ");
        List<StyledSegment> segs = List.of(
                new StyledSegment("Gain ", GRAY),
                new StyledSegment("56%", GREEN),
                new StyledSegment(" chance", GRAY)
        );
        TspRegistry encReg = new TspRegistry();
        String tsp = new TspEncoder(encReg, true).encode(segs);
        assertTrue(tsp.contains(":"), "encoded has HASH");

        Set<String> idHashSet = new HashSet<>();
        Map<String, List<Integer>> hashToIds = new HashMap<>();
        TspRegistry decReg = buildForDecode(segs, idHashSet, hashToIds);

        TspDecoder dec = new TspDecoder(decReg, idHashSet, hashToIds);
        List<StyledSegment> decoded = dec.decode(new TspParser(TspRecovery.Level.V1).parse(tsp));

        assertEq(3, decoded.size(), "3 segs");
        assertEq(GRAY, decoded.get(0).style(), "Gain GRAY");
        assertEq(GREEN, decoded.get(1).style(), "56% GREEN");
        assertEq(GRAY, decoded.get(2).style(), "chance GRAY");
        assertEq(0, dec.getRepairedCount(), "no repair");
        assertEq(0, dec.getLevel3Count(), "no level3");
        pass();
    }

    // Level 2: AI 跨色挪动（HASH 对 ID 错）-> 唯一匹配 -> 确定性修复
    private static void testLevel2Repair() {
        System.out.print("  Level 2 auto-repair (unique match) ... ");
        List<StyledSegment> segs = List.of(
                new StyledSegment("Gain ", GRAY),   // ID0
                new StyledSegment("56%", GREEN)      // ID1
        );
        String tsp = new TspEncoder(new TspRegistry(), true).encode(segs);
        Matcher m = Pattern.compile("\\[\\[(\\d+):([0-9a-fA-F]+)\\|\\|(.*?)\\]\\]").matcher(tsp);
        String hash0 = null, hash1 = null;
        while (m.find()) {
            if (m.group(1).equals("0")) hash0 = m.group(2);
            else hash1 = m.group(2);
        }
        // AI 跨色挪动：Gain (HASH0, ID0) 放 ID1；56% (HASH1, ID1) 放 ID0
        String aiMoved = "[[1:" + hash0 + "||获得]][[0:" + hash1 + "||56%]]";

        Set<String> idHashSet = new HashSet<>();
        Map<String, List<Integer>> hashToIds = new HashMap<>();
        TspRegistry decReg = buildForDecode(segs, idHashSet, hashToIds);
        TspDecoder dec = new TspDecoder(decReg, idHashSet, hashToIds);
        List<StyledSegment> decoded = dec.decode(new TspParser(TspRecovery.Level.V1).parse(aiMoved));

        assertEq(2, dec.getRepairedCount(), "2 repaired (unique match)");
        assertEq(0, dec.getLevel3Count(), "0 level3");
        assertEq(GRAY, decoded.get(0).style(), "获得 -> GRAY (repaired to ID0)");
        assertEq(GREEN, decoded.get(1).style(), "56% -> GREEN (repaired to ID1)");
        pass();
    }

    // Level 3a: AI 乱编 HASH -> invalid
    private static void testLevel3Invalid() {
        System.out.print("  Level 3a invalid HASH ... ");
        List<StyledSegment> segs = List.of(
                new StyledSegment("Gain ", GRAY),
                new StyledSegment("56%", GREEN)
        );
        Set<String> idHashSet = new HashSet<>();
        Map<String, List<Integer>> hashToIds = new HashMap<>();
        TspRegistry decReg = buildForDecode(segs, idHashSet, hashToIds);

        String aiInvalid = "[[0:dead||获得]][[1:beef||56%]]";
        TspDecoder dec = new TspDecoder(decReg, idHashSet, hashToIds);
        dec.decode(new TspParser(TspRecovery.Level.V1).parse(aiInvalid));

        assertEq(2, dec.getInvalidCount(), "2 invalid");
        assertEq(0, dec.getRepairedCount(), "0 repaired");
        pass();
    }

    // Level 3b: 同内容不同色 -> hashToIds 多匹配 -> AI 挪 -> ambiguous 不猜
    private static void testLevel3Ambiguous() {
        System.out.print("  Level 3b ambiguous (multi-match) ... ");
        // "Shen " 灰 (ID0) + "Shen" 青 (ID1) - 不同内容（带空格），但假设同 hash 场景
        // 用相同文本不同色构造多匹配：两段都 "Shen"，灰 + 青
        List<StyledSegment> segs = List.of(
                new StyledSegment("Shen", GRAY),   // ID0
                new StyledSegment("Shen", AQUA)    // ID1，同文本不同色 -> 同 hash 多 ID
        );
        Set<String> idHashSet = new HashSet<>();
        Map<String, List<Integer>> hashToIds = new HashMap<>();
        TspRegistry decReg = buildForDecode(segs, idHashSet, hashToIds);

        // 确认 hashToIds 多匹配
        String hash = TspEncoder.sha4("Shen");
        assertEq(2, hashToIds.get(hash).size(), "hashToIds[Shen] has 2 IDs (ambiguous)");

        // AI 把 "Shen" (hash 同) 放到不匹配的 ID（如 ID0 但应 ID1，或反之）
        // 因多匹配，decoder 不猜 -> ambiguous
        String aiAmbiguous = "[[0:" + hash + "||沈]]";  // hash 多匹配，ID0 可能对可能错
        TspDecoder dec = new TspDecoder(decReg, idHashSet, hashToIds);
        dec.decode(new TspParser(TspRecovery.Level.V1).parse(aiAmbiguous));

        // (0, hash) 在 idHashSet？如果 "Shen" 灰 ID0 的 (0,hash) 在集合 -> Level 0 正确，不 ambiguous
        // 要构造真正的 ambiguous：AI 把 "Shen" 放到不存在的 ID2
        String aiAmbiguous2 = "[[2:" + hash + "||沈]]";  // ID2 不存在，hash 多匹配
        TspDecoder dec2 = new TspDecoder(decReg, idHashSet, hashToIds);
        dec2.decode(new TspParser(TspRecovery.Level.V1).parse(aiAmbiguous2));

        assertEq(0, dec2.getRepairedCount(), "0 repaired (ambiguous, not unique)");
        assertEq(1, dec2.getAmbiguousCount(), "1 ambiguous (multi-match, not guess)");
        pass();
    }

    // v1.0 兼容：无 checksum 不校验
    private static void testV1CompatNoChecksum() {
        System.out.print("  v1.0 compat (no checksum) ... ");
        List<StyledSegment> segs = List.of(
                new StyledSegment("Gain ", GRAY),
                new StyledSegment("56%", GREEN)
        );
        String tsp = new TspEncoder(new TspRegistry(), false).encode(segs);
        assertTrue(!tsp.contains(":"), "v1.0 no HASH");

        Set<String> idHashSet = new HashSet<>();
        Map<String, List<Integer>> hashToIds = new HashMap<>();
        TspRegistry decReg = buildForDecode(segs, idHashSet, hashToIds);
        TspDecoder dec = new TspDecoder(decReg, idHashSet, hashToIds);
        List<StyledSegment> decoded = dec.decode(new TspParser(TspRecovery.Level.V1).parse(tsp));

        assertEq(0, dec.getRepairedCount(), "no repair (no checksum)");
        assertEq(0, dec.getLevel3Count(), "no level3 (no checksum)");
        assertEq(GRAY, decoded.get(0).style(), "Gain GRAY");
        assertEq(GREEN, decoded.get(1).style(), "56% GREEN");
        pass();
    }

    private static void assertEq(Object e, Object a, String l) {
        if (!e.equals(a)) throw new AssertionError(l + ": exp <" + e + "> got <" + a + ">");
    }
    private static void assertTrue(boolean c, String l) { if (!c) throw new AssertionError(l); }
    private static void pass() { passed++; System.out.println("PASS"); }
}
