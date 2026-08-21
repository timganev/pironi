package dev.pironi.cli;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Which build is running. */
public final class BuildVersion {
    private BuildVersion() {
    }

    private static volatile String cached;

    public static String current() {
        String known = cached;
        if (known == null) {
            known = resolve();
            cached = known;
        }
        return known;
    }

    private static String resolve() {
        String fromManifest = BuildVersion.class.getPackage() == null ? null
                : BuildVersion.class.getPackage().getImplementationVersion();
        if (fromManifest != null && !fromManifest.isBlank()) return fromManifest.trim();
        Path marker = versionFileBesideJar();
        if (marker != null && Files.isRegularFile(marker)) {
            try {
                String text = Files.readString(marker, StandardCharsets.UTF_8).strip();
                if (!text.isEmpty() && text.length() <= 40) return text;
            } catch (IOException ignored) {
                // fall through to dev
            }
        }
        return "dev";
    }

    private static Path versionFileBesideJar() {
        try {
            Path source = Path.of(BuildVersion.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path directory = Files.isDirectory(source) ? source : source.getParent();
            return directory == null ? null : directory.resolve("version.txt");
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }
}
