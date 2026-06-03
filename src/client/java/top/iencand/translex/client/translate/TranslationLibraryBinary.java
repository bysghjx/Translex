package top.iencand.translex.client.translate;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

/**
 * 预置库二进制格式读取器（按需 seek，只加载索引到内存）。
 *
 * 文件格式：
 *   Header (16B): magic[4] version[4] entryCount[4] indexOffset[4]
 *   Data (变长): UTF-8 JSON 条目依次排列
 *   Index (entryCount × 20B): keyHash[8] dataOffset[8] dataLength[4]
 */
public class TranslationLibraryBinary {
    private static final Logger LOGGER = LoggerFactory.getLogger("TranslationLibraryBin");
    private static final int MAGIC = 0x424C5854; // "TXLB" little-endian
    private static final int HEADER_SIZE = 16;
    private static final int INDEX_ENTRY_SIZE = 20;

    private final Gson gson = new Gson();
    private long[] hashes;       // 排序后的 key hash 数组
    private long[] offsets;       // 对应的数据偏移
    private int[] lengths;       // 对应的数据长度
    private int entryCount;
    private RandomAccessFile raf;

    public void load(File file) throws IOException {
        raf = new RandomAccessFile(file, "r");
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        raf.readFully(header.array());
        header.rewind();

        if (header.getInt() != MAGIC) {
            raf.close();
            throw new IOException("Invalid binary format magic");
        }
        int version = header.getInt();
        entryCount = header.getInt();
        int indexOffset = header.getInt();

        hashes = new long[entryCount];
        offsets = new long[entryCount];
        lengths = new int[entryCount];

        ByteBuffer indexBuf = ByteBuffer.allocate(entryCount * INDEX_ENTRY_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        raf.seek(indexOffset);
        raf.readFully(indexBuf.array());
        indexBuf.rewind();

        for (int i = 0; i < entryCount; i++) {
            hashes[i] = indexBuf.getLong();
            offsets[i] = indexBuf.getLong();
            lengths[i] = indexBuf.getInt();
        }

        LOGGER.info("Loaded binary library index: {} entries (v{})", entryCount, version);
    }

    public ItemPresetLibrary.ItemPreset query(String key) {
        if (raf == null || key == null) return null;
        long hash = hash64(key);
        int idx = Arrays.binarySearch(hashes, hash);
        if (idx < 0) return null;

        try {
            byte[] data = new byte[lengths[idx]];
            raf.seek(offsets[idx]);
            raf.readFully(data);
            String json = new String(data, StandardCharsets.UTF_8);
            return gson.fromJson(json, ItemPresetLibrary.ItemPreset.class);
        } catch (IOException e) {
            return null;
        }
    }

    public boolean isLoaded() {
        return raf != null;
    }

    public void close() {
        try { if (raf != null) raf.close(); } catch (IOException ignored) {}
    }

    public static long hash64(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    // ---- builder ----

    public static void build(File jsonInput, File binaryOutput) throws IOException {
        Gson g = new Gson();
        java.util.Map<String, ItemPresetLibrary.ItemPreset> items;
        try (Reader r = new InputStreamReader(new FileInputStream(jsonInput), StandardCharsets.UTF_8)) {
            var type = new com.google.gson.reflect.TypeToken<java.util.Map<String, ItemPresetLibrary.ItemPreset>>() {}.getType();
            items = g.fromJson(r, type);
        }
        if (items == null || items.isEmpty()) {
            throw new IOException("Empty or invalid JSON input");
        }

        record Entry(long hash, String key, String json) {}
        java.util.List<Entry> entries = new java.util.ArrayList<>();
        for (var e : items.entrySet()) {
            entries.add(new Entry(hash64(e.getKey()), e.getKey(), g.toJson(e.getValue())));
        }
        entries.sort(Comparator.comparingLong(Entry::hash));

        ByteArrayOutputStream dataBuf = new ByteArrayOutputStream();
        long[] dataOffsets = new long[entries.size()];
        int[] dataLengths = new int[entries.size()];

        for (int i = 0; i < entries.size(); i++) {
            byte[] b = entries.get(i).json().getBytes(StandardCharsets.UTF_8);
            dataOffsets[i] = HEADER_SIZE + dataBuf.size();
            dataLengths[i] = b.length;
            dataBuf.write(b);
        }

        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(binaryOutput)))) {
            ByteBuffer hdr = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            hdr.putInt(MAGIC);
            hdr.putInt(1); // version
            hdr.putInt(entries.size());
            hdr.putInt(HEADER_SIZE + dataBuf.size()); // indexOffset
            out.write(hdr.array());

            out.write(dataBuf.toByteArray());

            ByteBuffer idx = ByteBuffer.allocate(entries.size() * INDEX_ENTRY_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < entries.size(); i++) {
                idx.putLong(entries.get(i).hash());
                idx.putLong(dataOffsets[i]);
                idx.putInt(dataLengths[i]);
            }
            out.write(idx.array());
        }

        LOGGER.info("Built binary library: {} entries -> {}", entries.size(), binaryOutput.getAbsolutePath());
    }
}
