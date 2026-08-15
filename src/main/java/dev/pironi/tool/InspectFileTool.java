package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.safety.AccessGrants;
import dev.pironi.safety.Workspace;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/** Bounded, content-free diagnostics for binary and large files. */
public final class InspectFileTool implements Tool {
    private volatile AccessGrants grants = new AccessGrants();
    private static final int SAMPLE_BYTES = 4096;
    private final Workspace workspace;
    private final List<Path> readRoots;

    public InspectFileTool(Workspace workspace, List<Path> readRoots) {
        this.workspace = workspace;
        this.readRoots = readRoots.stream().map(path -> path.toAbsolutePath().normalize()).toList();
    }
    @Override public String name() { return "inspect_file"; }
    @Override public String description() { return "Inspect file size, SHA-256, timestamps, newline count and UTF-8/binary classification without returning file contents; safe for large or binary files."; }
    @Override public String argumentSchema() { return "{\"path\":\"workspace-relative or absolute path under a configured search root\"}"; }
    @Override public boolean mutating() { return false; }

    @Override public ToolResult execute(JsonNode arguments) {
        try {
            String supplied = ToolArguments.requiredText(arguments, "path");
            Path path = resolve(supplied);
            long size = Files.size(path); long lf = 0; long crlf = 0; int previous = -1;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] sample = new byte[(int) Math.min(SAMPLE_BYTES, size)]; int sampleLength = 0;
            try (var input = new BufferedInputStream(Files.newInputStream(path))) {
                byte[] buffer = new byte[64 * 1024]; int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (sampleLength < sample.length) { int copy = Math.min(read, sample.length - sampleLength); System.arraycopy(buffer, 0, sample, sampleLength, copy); sampleLength += copy; }
                    digest.update(buffer, 0, read);
                    for (int index = 0; index < read; index++) { int value = buffer[index] & 0xff; if (value == '\n') { lf++; if (previous == '\r') crlf++; } previous = value; }
                }
            }
            boolean hasNull = false; for (int i = 0; i < sampleLength; i++) if (sample[i] == 0) { hasNull = true; break; }
            boolean validUtf8 = validUtf8(sample, sampleLength);
            String output = "path=" + path + "\nsizeBytes=" + size + "\nsha256=" + HexFormat.of().formatHex(digest.digest())
                    + "\nmodifiedUtc=" + Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis())
                    + "\nclassification=" + (validUtf8 && !hasNull ? "utf-8-text" : "binary-or-non-utf8")
                    + "\nlfCount=" + lf + "\ncrlfCount=" + crlf + "\nsampleBytesExamined=" + sampleLength;
            return ToolResult.success(output);
        } catch (IllegalArgumentException | IOException | NoSuchAlgorithmException e) { return ToolResult.failure(e.getMessage()); }
    }

    private Path resolve(String supplied) throws IOException {
        Path path = Path.of(supplied);
        if (!path.isAbsolute()) return workspace.resolveExisting(supplied);
        Path real = path.toRealPath();
        if (effectiveRoots().stream().noneMatch(root -> real.startsWith(canonical(root)))) throw new IOException("Path is outside configured search roots: " + supplied);
        return real;
    }
    private static Path canonical(Path path) { try { return path.toRealPath(); } catch (IOException e) { return path.toAbsolutePath().normalize(); } }
    private static boolean validUtf8(byte[] bytes, int length) {
        try { StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, length)); return true; }
        catch (CharacterCodingException e) { return false; }
    }

    /** Lets the interactive shell widen this tool's reach without a restart. */
    public void useGrants(AccessGrants sessionGrants) {
        if (sessionGrants != null) this.grants = sessionGrants;
    }

    private java.util.List<java.nio.file.Path> effectiveRoots() {
        java.util.List<java.nio.file.Path> all = new java.util.ArrayList<>(readRoots);
        all.addAll(grants.grantedRoots());
        return all;
    }
}
