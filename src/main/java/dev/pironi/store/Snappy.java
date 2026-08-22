package dev.pironi.store;

import java.io.IOException;

/**
 * Raw Snappy, without the stream framing: LevelDB compresses each block on its own and writes the
 * uncompressed length in front of it. This is here rather than as a dependency because the release
 * would then carry a library for one file format, and the format is ninety lines.
 */
final class Snappy {
    private Snappy() {
    }

    /** @throws IOException on anything malformed, so a corrupt block is skipped rather than fatal */
    static byte[] decompress(byte[] input, int offset, int length) throws IOException {
        try {
            int[] cursor = {offset};
            int end = offset + length;
            int size = varint(input, cursor, end);
            if (size < 0 || size > 64 * 1024 * 1024) {
                throw new IOException("implausible uncompressed size " + size);
            }
            byte[] out = new byte[size];
            int written = 0;
            int i = cursor[0];
            while (i < end) {
                int tag = input[i++] & 0xff;
                if ((tag & 0x03) == 0) {
                    int literal = tag >>> 2;
                    if (literal >= 60) {
                        int extra = literal - 59;
                        literal = 0;
                        for (int b = 0; b < extra; b++) literal |= (input[i + b] & 0xff) << (8 * b);
                        i += extra;
                    }
                    literal++;
                    System.arraycopy(input, i, out, written, literal);
                    i += literal;
                    written += literal;
                    continue;
                }
                int copyLength;
                int copyOffset;
                switch (tag & 0x03) {
                    case 1 -> {
                        copyLength = 4 + ((tag >>> 2) & 0x07);
                        copyOffset = ((tag >>> 5) << 8) | (input[i++] & 0xff);
                    }
                    case 2 -> {
                        copyLength = 1 + (tag >>> 2);
                        copyOffset = (input[i] & 0xff) | ((input[i + 1] & 0xff) << 8);
                        i += 2;
                    }
                    default -> {
                        copyLength = 1 + (tag >>> 2);
                        copyOffset = (input[i] & 0xff) | ((input[i + 1] & 0xff) << 8)
                                | ((input[i + 2] & 0xff) << 16) | ((input[i + 3] & 0xff) << 24);
                        i += 4;
                    }
                }
                int from = written - copyOffset;
                if (copyOffset <= 0 || from < 0) throw new IOException("copy points outside output");
                // Overlapping copies are how Snappy expresses a repeat, so this cannot become an
                // arraycopy: the source is still being written as it is read.
                for (int c = 0; c < copyLength; c++) out[written++] = out[from++];
            }
            if (written != size) throw new IOException("expected " + size + " bytes, produced " + written);
            return out;
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IOException("truncated snappy block", e);
        }
    }

    static int varint(byte[] data, int[] cursor, int limit) throws IOException {
        int result = 0;
        int shift = 0;
        while (cursor[0] < limit) {
            int b = data[cursor[0]++] & 0xff;
            result |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
            if (shift > 28) throw new IOException("varint longer than 32 bits");
        }
        throw new IOException("varint ran past the end of the block");
    }
}
