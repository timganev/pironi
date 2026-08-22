package dev.pironi.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LevelDbStoreTest {

    @Test
    void readsATable(@TempDir Path directory) throws IOException {
        Files.write(directory.resolve("000005.ldb"), table(List.of(
                new String[]{"key-a", "{\"subject\":\"stand-up\"}"},
                new String[]{"key-b", "{\"subject\":\"review\"}"}
        )));

        LevelDbStore.Scan scan = LevelDbStore.read(directory, 100);

        assertEquals(1, scan.filesRead());
        assertEquals(2, scan.records().size());
        assertEquals("key-a", text(scan.records().get(0).key()));
        assertEquals("{\"subject\":\"stand-up\"}", text(scan.records().get(1 - 1).value()));
        assertEquals("key-b", text(scan.records().get(1).key()));
        assertEquals(0, scan.blocksSkipped());
        assertEquals(List.of(), scan.problems());
    }

    @Test
    void readsTheWriteAheadLogAsWell() throws IOException {
        Path directory = Files.createTempDirectory("leveldb-log");
        Files.write(directory.resolve("000004.log"), log(List.<String[]>of(
                new String[]{"pending-key", "{\"subject\":\"not yet compacted\"}"}
        )));

        LevelDbStore.Scan scan = LevelDbStore.read(directory, 100);

        // Reading only the tables loses whatever the application has not compacted yet, which on a
        // running client is exactly the newest thing the user is asking about.
        assertEquals(1, scan.records().size());
        assertEquals("pending-key", text(scan.records().getFirst().key()));
        assertTrue(text(scan.records().getFirst().value()).contains("not yet compacted"));
    }

    @Test
    void stopsAtTheRecordCeiling(@TempDir Path directory) throws IOException {
        Files.write(directory.resolve("000005.ldb"), table(List.of(
                new String[]{"a", "1"}, new String[]{"b", "2"}, new String[]{"c", "3"}
        )));

        assertEquals(2, LevelDbStore.read(directory, 2).records().size());
    }

    @Test
    void namesAFileItCouldNotParseInsteadOfReturningNothing(@TempDir Path directory)
            throws IOException {
        Files.write(directory.resolve("000005.ldb"), "not a table at all".getBytes(StandardCharsets.UTF_8));

        LevelDbStore.Scan scan = LevelDbStore.read(directory, 100);

        // Silence here reads as "the store is empty", which is the one answer that must not be
        // guessed at: an unreadable store and an empty one look identical in any summary.
        assertEquals(0, scan.records().size());
        assertEquals(1, scan.problems().size());
        assertTrue(scan.problems().getFirst().contains("000005.ldb"));
    }

    @Test
    void refusesAPathThatIsNotAStore(@TempDir Path directory) {
        assertThrows(IOException.class,
                () -> LevelDbStore.read(directory.resolve("absent"), 100));
    }

    @Test
    void snappyRebuildsAnOverlappingCopy() throws IOException {
        // A copy whose length runs past its own start is how Snappy writes a repeat, so the bytes
        // have to be produced one at a time - an arraycopy reads the source before it is written.
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        compressed.write(10);                       // uncompressed length
        compressed.write((2 - 1) << 2);             // literal, two bytes
        compressed.write('a');
        compressed.write('b');
        compressed.write(((8 - 1) << 2) | 2);       // copy of eight, two-byte offset
        compressed.write(2);
        compressed.write(0);

        byte[] out = Snappy.decompress(compressed.toByteArray(), 0, compressed.size());

        assertEquals("ababababab", new String(out, StandardCharsets.UTF_8));
    }

    @Test
    void snappyRefusesACorruptBlockRatherThanReturningRubbish() {
        byte[] truncated = {20, (byte) ((5 - 1) << 2), 'a', 'b'};

        assertThrows(IOException.class, () -> Snappy.decompress(truncated, 0, truncated.length));
    }

    // ---- fixtures ----------------------------------------------------------------------------

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * A LevelDB table small enough that every offset fits in one varint byte: one uncompressed data
     * block, an index block naming it, and the 48-byte footer.
     */
    private static byte[] table(List<String[]> pairs) throws IOException {
        byte[] data = blockContents(pairs);
        byte[] index = blockContents(List.<String[]>of(new String[]{
                pairs.getLast()[0], new String(new byte[]{0, (byte) data.length}, StandardCharsets.ISO_8859_1)
        }));
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(data);
        file.write(0);                              // no compression
        file.write(new byte[4]);                    // crc, which this reader does not check
        int indexOffset = file.size();
        file.write(index);
        file.write(0);
        file.write(new byte[4]);

        ByteArrayOutputStream footer = new ByteArrayOutputStream();
        footer.write(0);                            // metaindex offset
        footer.write(0);                            // metaindex size
        footer.write(indexOffset);
        footer.write(index.length);
        while (footer.size() < 40) footer.write(0);
        footer.write(new byte[]{0x57, (byte) 0xfb, (byte) 0x80, (byte) 0x8b,
                0x24, 0x75, 0x47, (byte) 0xdb});
        file.write(footer.toByteArray());
        return file.toByteArray();
    }

    private static byte[] blockContents(List<String[]> pairs) throws IOException {
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        for (String[] pair : pairs) {
            byte[] key = pair[0].getBytes(StandardCharsets.UTF_8);
            byte[] value = pair[1].getBytes(StandardCharsets.ISO_8859_1);
            block.write(0);                         // shared with the previous key
            block.write(key.length);
            block.write(value.length);
            block.write(key);
            block.write(value);
        }
        block.write(new byte[]{0, 0, 0, 0});        // one restart, at offset zero
        block.write(new byte[]{1, 0, 0, 0});        // and there is one of them
        return block.toByteArray();
    }

    /** One full log record carrying a write batch of puts. */
    private static byte[] log(List<String[]> pairs) throws IOException {
        ByteArrayOutputStream batch = new ByteArrayOutputStream();
        batch.write(new byte[8]);                   // sequence number
        batch.write(new byte[]{(byte) pairs.size(), 0, 0, 0});
        for (String[] pair : pairs) {
            byte[] key = pair[0].getBytes(StandardCharsets.UTF_8);
            byte[] value = pair[1].getBytes(StandardCharsets.UTF_8);
            batch.write(1);                         // a put
            batch.write(key.length);
            batch.write(key);
            batch.write(value.length);
            batch.write(value);
        }
        byte[] payload = batch.toByteArray();
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(new byte[4]);                    // crc
        file.write(payload.length & 0xff);
        file.write((payload.length >>> 8) & 0xff);
        file.write(1);                              // a whole record, not a fragment
        file.write(payload);
        return file.toByteArray();
    }
}
