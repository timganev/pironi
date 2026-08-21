package dev.pironi.tool;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/** How far the reading tools reach, in the words the model reads. */
public final class ReadReach {
    private ReadReach() {
    }

    public static String describe(List<Path> allowedRoots) {
        return coversWholeMachine(allowedRoots)
                ? "Any readable path on this machine is allowed, including absolute paths outside "
                        + "the workspace; writing stays inside the workspace."
                : "Allowed roots: " + allowedRoots;
    }

    private static boolean coversWholeMachine(List<Path> allowedRoots) {
        Set<Path> filesystemRoots = StreamSupport
                .stream(FileSystems.getDefault().getRootDirectories().spliterator(), false)
                .map(root -> root.toAbsolutePath().normalize())
                .collect(Collectors.toSet());
        return allowedRoots.stream()
                .map(root -> root.toAbsolutePath().normalize())
                .anyMatch(filesystemRoots::contains);
    }
}
