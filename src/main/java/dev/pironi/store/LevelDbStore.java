package dev.pironi.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Reads a Chromium LevelDB directory - what IndexedDB, Local Storage and every WebView2 app store
 * their records in - without a native library and without stopping the application that owns it.
 *
 * <p>Two file kinds hold the data. A {@code .ldb} table is the settled part, written in blocks
 * that are usually Snappy-compressed; a {@code .log} is the write-ahead log holding whatever has
 * not been folded into a table yet, uncompressed. Reading only one of them silently loses either
 * the history or the most recent changes, so both are read.
 *
 * <p>What is deliberately not done here: no key ordering, no sequence-number resolution, no
 * tombstone handling. A key that was deleted or overwritten still shows its older values, because
 * when reading someone else's store that is usually the point. This is an archaeologist's reader,
 * not a database.
 */
public final class LevelDbStore {
    /** Chromium raises table sizes over time; this is a ceiling on one file, not on the store. */
    private static final long MAX_FILE_BYTES = 256L * 1024 * 1024;
    private static final int LOG_BLOCK = 32768;
    private static final byte[] TABLE_MAGIC = {
            0x57, (byte) 0xfb, (byte) 0x80, (byte) 0x8b, 0x24, 0x75, 0x47, (byte) 0xdb
    };

    /** One stored record. Both halves are raw: keys are structured, values are V8-serialised. */
    public record Record(String source, byte[] key, byte[] value) {
    }

    /**
     * What a pass over the store found, including what it could not read. A count of skipped
     * blocks is the difference between "the calendar is empty" and "this build compresses
     * differently", and those look identical in any summary that leaves it out.
     */
    public record Scan(List<Record> records, int filesRead, int blocksSkipped,
            List<String> problems) {
    }

    private LevelDbStore() {
    }

    /**
     * @param directory  a {@code *.leveldb} directory
     * @param maxRecords stop after this many, so a store of any size stays bounded
     */
    public static Scan read(Path directory, int maxRecords) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IOException("Not a LevelDB directory: " + directory);
        }
        List<Record> records = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int[] skipped = {0};
        int filesRead = 0;
        List<Path> files;
        try (var stream = Files.list(directory)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(LevelDbStore::holdsRecords)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        for (Path file : files) {
            if (records.size() >= maxRecords) break;
            try {
                long size = Files.size(file);
                if (size > MAX_FILE_BYTES) {
                    problems.add(file.getFileName() + ": " + size + " bytes, over the read ceiling");
                    continue;
                }
                // Java opens for reading with every share flag set, so this works while the owning
                // application holds the file open - which it will, because it is running.
                byte[] data = Files.readAllBytes(file);
                String name = file.getFileName().toString();
                if (name.endsWith(".log")) {
                    readLog(name, data, records, maxRecords);
                } else {
                    readTable(name, data, records, maxRecords, skipped, problems);
                }
                filesRead++;
            } catch (IOException | RuntimeException e) {
                problems.add(file.getFileName() + ": " + e.getMessage());
            }
        }
        return new Scan(List.copyOf(records), filesRead, skipped[0], List.copyOf(problems));
    }

    private static boolean holdsRecords(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".ldb") || name.endsWith(".sst") || name.endsWith(".log");
    }

    // ---- tables ------------------------------------------------------------------------------

    private static void readTable(String source, byte[] data, List<Record> into, int max,
            int[] skipped, List<String> problems) throws IOException {
        if (data.length < 48 || !Arrays.equals(data, data.length - 8, data.length,
                TABLE_MAGIC, 0, 8)) {
            throw new IOException("not a LevelDB table (footer magic missing)");
        }
        int[] cursor = {data.length - 48};
        varint64(data, cursor);
        varint64(data, cursor);
        long indexOffset = varint64(data, cursor);
        long indexSize = varint64(data, cursor);
        byte[] index = block(data, indexOffset, indexSize);
        if (index == null) {
            throw new IOException("index block uses a compression this reader does not know");
        }
        for (byte[][] entry : entries(index)) {
            if (into.size() >= max) return;
            int[] handle = {0};
            long offset = varint64(entry[1], handle);
            long size = varint64(entry[1], handle);
            byte[] contents;
            try {
                contents = block(data, offset, size);
            } catch (IOException e) {
                problems.add(source + ": " + e.getMessage());
                skipped[0]++;
                continue;
            }
            if (contents == null) {
                skipped[0]++;
                continue;
            }
            for (byte[][] record : entries(contents)) {
                if (into.size() >= max) return;
                into.add(new Record(source, record[0], record[1]));
            }
        }
    }

    /** @return the block contents, or null when compressed in a way this reader cannot undo */
    private static byte[] block(byte[] data, long offset, long size) throws IOException {
        int start = (int) offset;
        int length = (int) size;
        if (start < 0 || length < 0 || start + length + 5 > data.length) {
            throw new IOException("block handle points outside the file");
        }
        int compression = data[start + length] & 0xff;
        return switch (compression) {
            case 0 -> Arrays.copyOfRange(data, start, start + length);
            case 1 -> Snappy.decompress(data, start, length);
            // 2 is zlib, 3 bzip2, 4 zstd. Chromium has been moving towards zstd, so this is the
            // shape in which a future build breaks - and it has to be counted, not passed over.
            default -> null;
        };
    }

    /** Entries of one block, each as key then value. Keys are prefix-compressed against the last. */
    private static List<byte[][]> entries(byte[] block) throws IOException {
        if (block.length < 4) return List.of();
        int restarts = int32(block, block.length - 4);
        int limit = block.length - 4 - restarts * 4;
        if (limit < 0 || restarts < 0) throw new IOException("block trailer is not a restart array");
        List<byte[][]> found = new ArrayList<>();
        int[] cursor = {0};
        byte[] previousKey = new byte[0];
        while (cursor[0] < limit) {
            int shared = Snappy.varint(block, cursor, limit);
            int unshared = Snappy.varint(block, cursor, limit);
            int valueLength = Snappy.varint(block, cursor, limit);
            if (shared > previousKey.length || unshared < 0 || valueLength < 0
                    || cursor[0] + unshared + valueLength > block.length) {
                throw new IOException("entry runs past the end of the block");
            }
            byte[] key = new byte[shared + unshared];
            System.arraycopy(previousKey, 0, key, 0, shared);
            System.arraycopy(block, cursor[0], key, shared, unshared);
            cursor[0] += unshared;
            byte[] value = Arrays.copyOfRange(block, cursor[0], cursor[0] + valueLength);
            cursor[0] += valueLength;
            found.add(new byte[][]{key, value});
            previousKey = key;
        }
        return found;
    }

    // ---- write-ahead log ---------------------------------------------------------------------

    /**
     * The log is 32 KB blocks of length-prefixed fragments, and one record may span blocks. What
     * each record carries is a write batch: a sequence number, a count, then put and delete
     * entries.
     */
    private static void readLog(String source, byte[] data, List<Record> into, int max) {
        java.io.ByteArrayOutputStream pending = new java.io.ByteArrayOutputStream();
        int position = 0;
        while (position + 7 <= data.length && into.size() < max) {
            int remaining = LOG_BLOCK - (position % LOG_BLOCK);
            if (remaining < 7) {
                position += remaining;
                continue;
            }
            int length = (data[position + 4] & 0xff) | ((data[position + 5] & 0xff) << 8);
            int type = data[position + 6] & 0xff;
            position += 7;
            if (type == 0 || length <= 0 || position + length > data.length) break;
            pending.write(data, position, length);
            position += length;
            // 1 is a whole record; 4 closes one that began in an earlier block.
            if (type == 1 || type == 4) {
                readBatch(source, pending.toByteArray(), into, max);
                pending.reset();
            }
        }
    }

    private static void readBatch(String source, byte[] batch, List<Record> into, int max) {
        if (batch.length < 12) return;
        int[] cursor = {12};
        try {
            while (cursor[0] < batch.length && into.size() < max) {
                int kind = batch[cursor[0]++] & 0xff;
                if (kind != 0 && kind != 1) return;
                int keyLength = Snappy.varint(batch, cursor, batch.length);
                byte[] key = Arrays.copyOfRange(batch, cursor[0], cursor[0] + keyLength);
                cursor[0] += keyLength;
                if (kind == 0) continue;
                int valueLength = Snappy.varint(batch, cursor, batch.length);
                byte[] value = Arrays.copyOfRange(batch, cursor[0], cursor[0] + valueLength);
                cursor[0] += valueLength;
                into.add(new Record(source, key, value));
            }
        } catch (IOException | ArrayIndexOutOfBoundsException | NegativeArraySizeException e) {
            // A half-written batch at the tail of the log is normal rather than a fault: the
            // application was in the middle of a write. Everything before it is already collected.
        }
    }

    // ---- primitives --------------------------------------------------------------------------

    private static long varint64(byte[] data, int[] cursor) throws IOException {
        long result = 0;
        int shift = 0;
        while (cursor[0] < data.length) {
            int b = data[cursor[0]++] & 0xff;
            result |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
            if (shift > 63) throw new IOException("varint longer than 64 bits");
        }
        throw new IOException("varint ran past the end of the file");
    }

    private static int int32(byte[] data, int at) {
        return (data[at] & 0xff) | ((data[at + 1] & 0xff) << 8)
                | ((data[at + 2] & 0xff) << 16) | ((data[at + 3] & 0xff) << 24);
    }
}
