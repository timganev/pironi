package dev.pironi.tool;

import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;

/**
 * Turns an exception into something the model can act on.
 *
 * <p>The file system exceptions carry the path and nothing else: {@code getMessage()} on a
 * {@link NoSuchFileException} is the path, on its own, with no word anywhere saying what went
 * wrong. Handed back as a tool failure it reads as an unexplained refusal, and a run this evening
 * shows what that costs - the agent rewrote a script it had just written, because "failed" and a
 * path is indistinguishable from "the file is there and something else broke".
 *
 * <p>Windows makes it worse in one direction and better in another: a locked file arrives as a
 * plain {@link FileSystemException} whose reason says exactly what holds it, while a missing one
 * arrives bare. So the reason is used wherever there is one, and supplied where there is not.
 */
public final class ToolFailure {
    private ToolFailure() {
    }

    public static String describe(Throwable failure) {
        if (failure == null) return "failed for an unknown reason";
        String named = named(failure);
        if (named != null) return named;
        String message = failure.getMessage();
        // A cause with nothing to say leaves only its type, which at least names the kind of
        // fault instead of an empty line.
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message.strip();
    }

    private static String named(Throwable failure) {
        return switch (failure) {
            case NoSuchFileException e -> "no such file or directory: " + paths(e);
            case AccessDeniedException e -> "access denied: " + paths(e) + reason(e);
            case NotDirectoryException e -> "not a directory: " + paths(e);
            case DirectoryNotEmptyException e -> "directory is not empty: " + paths(e);
            case FileAlreadyExistsException e -> "already exists: " + paths(e);
            case FileSystemException e -> e.getReason() == null || e.getReason().isBlank()
                    ? "file system error: " + paths(e)
                    : e.getReason().strip() + ": " + paths(e);
            default -> null;
        };
    }

    /** A move or copy names two paths, and which one refused matters. */
    private static String paths(FileSystemException failure) {
        String file = failure.getFile() == null ? "(unnamed)" : failure.getFile();
        return failure.getOtherFile() == null ? file : file + " -> " + failure.getOtherFile();
    }

    private static String reason(FileSystemException failure) {
        return failure.getReason() == null || failure.getReason().isBlank()
                ? "" : " (" + failure.getReason().strip() + ")";
    }
}
