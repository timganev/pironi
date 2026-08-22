package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.safety.AccessGrants;
import dev.pironi.safety.Workspace;
import dev.pironi.store.LevelDbStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads a Chromium LevelDB store - IndexedDB or Local Storage - as text. Teams, the new Outlook,
 * Edge and every WebView2 application keep their local data this way, and none of it is reachable
 * through a file read: the records are in prefix-compressed blocks, usually Snappy-compressed.
 *
 * <p>Scanning such a store from PowerShell takes minutes and looks like a hang; this takes about a
 * fifth of a second for thirty thousand records, which is the whole reason it exists as a tool.
 */
public final class ReadLevelDbTool implements Tool {
    static final int DEFAULT_MAX_RECORDS = 20_000;
    static final int DEFAULT_MAX_CHARACTERS = 20_000;
    /** Below this a run of two-byte characters is as likely to be coincidence as text. */
    private static final int MIN_UTF16_RUN = 6;

    private volatile AccessGrants grants = new AccessGrants();
    private final Workspace workspace;
    private final List<Path> readRoots;

    public ReadLevelDbTool(Workspace workspace, List<Path> readRoots) {
        this.workspace = workspace;
        this.readRoots = readRoots.stream().map(path -> path.toAbsolutePath().normalize()).toList();
    }

    @Override
    public String name() {
        return "read_leveldb";
    }

    @Override
    public String description() {
        return "Read a Chromium LevelDB directory (IndexedDB or Local Storage, as used by Teams, "
                + "the new Outlook, Edge and other WebView2 apps) and return its records as text. "
                + "Works while the owning application is running. Give the path of the "
                + "*.leveldb directory itself. " + ReadReach.describe(readRoots);
    }

    @Override
    public String argumentSchema() {
        return "{\"path\":\"path of the .leveldb directory\","
                + "\"contains\":\"optional: only records whose text holds this, case-insensitive\","
                + "\"maxRecords\":\"optional: records to scan, default " + DEFAULT_MAX_RECORDS + "\","
                + "\"maxCharacters\":\"optional: output ceiling, default " + DEFAULT_MAX_CHARACTERS + "\"}";
    }

    @Override
    public boolean mutating() {
        return false;
    }

    @Override
    public boolean requiresExplicitApproval(JsonNode arguments) {
        JsonNode supplied = arguments == null ? null : arguments.get("path");
        if (supplied == null || !supplied.isTextual()) return false;
        try {
            return dev.pironi.safety.SecretStores.isProtected(Path.of(supplied.textValue()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public ToolResult execute(JsonNode arguments) {
        try {
            Path directory = resolve(ToolArguments.requiredText(arguments, "path"));
            String contains = text(arguments, "contains");
            int maxRecords = number(arguments, "maxRecords", DEFAULT_MAX_RECORDS);
            int maxCharacters = number(arguments, "maxCharacters", DEFAULT_MAX_CHARACTERS);

            LevelDbStore.Scan scan = LevelDbStore.read(directory, maxRecords);
            String needle = contains == null ? null : contains.toLowerCase(Locale.ROOT);

            StringBuilder body = new StringBuilder();
            int matched = 0;
            int omitted = 0;
            for (LevelDbStore.Record record : scan.records()) {
                String key = readable(record.key());
                String value = readable(record.value());
                if (needle != null
                        && !value.toLowerCase(Locale.ROOT).contains(needle)
                        && !key.toLowerCase(Locale.ROOT).contains(needle)) {
                    continue;
                }
                matched++;
                String entry = "-- " + record.source() + " key=" + key + "\n" + value + "\n";
                if (body.length() + entry.length() > maxCharacters) {
                    omitted++;
                    continue;
                }
                body.append(entry);
            }
            return ToolResult.success(header(scan, matched, omitted, needle, maxRecords) + body);
        } catch (IllegalArgumentException | IOException e) {
            return ToolResult.failure(e.getMessage());
        }
    }

    /**
     * Every number a reader of this output needs in order to distrust it: an empty result after a
     * skipped block means the build changed, not that the store is empty, and a record count equal
     * to the ceiling means the scan stopped early rather than finished.
     */
    private static String header(LevelDbStore.Scan scan, int matched, int omitted, String needle,
            int maxRecords) {
        StringBuilder head = new StringBuilder();
        head.append("files=").append(scan.filesRead())
                .append(" recordsScanned=").append(scan.records().size())
                .append(" matched=").append(matched);
        if (needle == null) head.append(" (no filter)");
        if (scan.records().size() >= maxRecords) {
            head.append("\nThe scan stopped at the maxRecords ceiling, so this is not the whole ")
                    .append("store. Filter with 'contains' or raise maxRecords.");
        }
        if (omitted > 0) {
            head.append("\n").append(omitted)
                    .append(" further matches were left out to stay inside the output ceiling; ")
                    .append("narrow 'contains' or raise 'maxCharacters' to see them.");
        }
        if (scan.blocksSkipped() > 0) {
            head.append("\n").append(scan.blocksSkipped())
                    .append(" blocks use a compression this reader does not know (zlib or zstd). ")
                    .append("Whatever was in them is missing from the result.");
        }
        for (String problem : scan.problems()) head.append("\nproblem: ").append(problem);
        return head.append("\n").toString();
    }

    /**
     * Renders a record as text. Values hold V8-serialised data: JSON payloads arrive as one-byte
     * strings and read straight, while anything not written in Latin-1 - a Bulgarian subject line,
     * for one - is a two-byte string and would otherwise come out as alternating rubbish.
     */
    static String readable(byte[] data) {
        StringBuilder out = new StringBuilder(data.length);
        int i = 0;
        while (i < data.length) {
            int run = utf16RunLength(data, i);
            if (run >= MIN_UTF16_RUN) {
                out.append(new String(data, i, run * 2, StandardCharsets.UTF_16LE));
                i += run * 2;
                continue;
            }
            int b = data[i] & 0xff;
            if (b >= 0x20) {
                int length = utf8Length(data, i);
                out.append(new String(data, i, length, StandardCharsets.UTF_8));
                i += length;
            } else {
                // Length prefixes and type tags: keep one mark so fields do not run together.
                out.append('.');
                i++;
            }
        }
        return out.toString();
    }

    /**
     * How many two-byte characters start here. The high byte has to be 0x08 or below, which is what
     * separates real UTF-16 text from a pair of ASCII bytes that happens to decode to something
     * printable - the second byte of ASCII text is always 0x20 or more.
     */
    private static int utf16RunLength(byte[] data, int from) {
        int count = 0;
        for (int i = from; i + 1 < data.length; i += 2) {
            int high = data[i + 1] & 0xff;
            char c = (char) ((data[i] & 0xff) | (high << 8));
            if (high > 0x08 || c < 0x20 || Character.isISOControl(c)) break;
            count++;
        }
        return count;
    }

    /** Keeps a multi-byte UTF-8 character whole, so Cyrillic in a one-byte string survives. */
    private static int utf8Length(byte[] data, int at) {
        int lead = data[at] & 0xff;
        int length = lead < 0xc0 ? 1 : lead < 0xe0 ? 2 : lead < 0xf0 ? 3 : 4;
        if (at + length > data.length) return 1;
        for (int i = 1; i < length; i++) {
            if ((data[at + i] & 0xc0) != 0x80) return 1;
        }
        return length;
    }

    private static String text(JsonNode arguments, String field) {
        JsonNode node = arguments == null ? null : arguments.get(field);
        if (node == null || !node.isTextual() || node.textValue().isBlank()) return null;
        return node.textValue();
    }

    private static int number(JsonNode arguments, String field, int fallback) {
        JsonNode node = arguments == null ? null : arguments.get(field);
        if (node == null || !node.canConvertToInt()) return fallback;
        int value = node.asInt();
        return value <= 0 ? fallback : value;
    }

    private Path resolve(String supplied) throws IOException {
        Path path = dev.pironi.safety.UserPath.of(supplied);
        if (!path.isAbsolute()) return workspace.resolveExisting(supplied);
        Path real = path.toRealPath();
        if (effectiveRoots().stream().noneMatch(root -> real.startsWith(canonical(root)))) {
            throw new IOException("Path is outside configured search roots: " + supplied);
        }
        return real;
    }

    private static Path canonical(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    /** Lets the interactive shell widen this tool's reach without a restart. */
    public void useGrants(AccessGrants sessionGrants) {
        if (sessionGrants != null) this.grants = sessionGrants;
    }

    private List<Path> effectiveRoots() {
        List<Path> all = new ArrayList<>(readRoots);
        all.addAll(grants.grantedRoots());
        return all;
    }
}
